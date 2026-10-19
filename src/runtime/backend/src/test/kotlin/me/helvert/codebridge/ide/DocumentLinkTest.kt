package me.helvert.codebridge.ide

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeDocumentLinkReferenceProvider
import me.helvert.codebridge.language.CodeBridgeDocumentLinkSymbol
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLinkTest : EditorFixture() {

    /** What the host was asked, as `method args`. */
    private val asked = ConcurrentLinkedQueue<String>()

    @Test
    fun `ctrl hover underlines a link and asks once per text`() = onEdt {
        ImplicitReferenceProvider.EP_NAME.point.registerExtension(
            CodeBridgeDocumentLinkReferenceProvider(),
            fixture.testRootDisposable
        )
        val features = open(target())

        val data = GotoDeclarationAction().getCtrlMouseData(fixture.editor, fixture.file, 6)
        GotoDeclarationAction().getCtrlMouseData(fixture.editor, fixture.file, 20)

        assertEquals(listOf(TextRange(4, 13)), data?.ranges)
        assertEquals(true, data?.isNavigatable)
        assertEquals(1, asked.count { it.startsWith($$"$provideDocumentLinks") }, "$asked")
        assertTrue(asked.first().startsWith($$"$provideDocumentLinks [5,{"), "$asked")
        features.hostStopped()
    }

    @Test
    fun `following a link to a file opens it at the line its fragment names`() = onEdt {
        val target = target()
        val features = open(target)

        follow(6)

        // Resolved, then opened: neither need be done by the time the events queued so far are.
        PlatformTestUtil.waitWithEventsDispatching(
            "the link's target never opened",
            { FileEditorManager.getInstance(fixture.project).selectedTextEditor?.let(::fileOf) == target },
            10,
        )
        val editor = FileEditorManager.getInstance(fixture.project).selectedTextEditor
        assertEquals(target, editor?.let(::fileOf))
        // Line 2, column 3: "two" begins at 4, and two characters in.
        assertEquals(6, editor?.caretModel?.offset)
        features.hostStopped()
    }

    @Test
    fun `a link left unresolved is resolved when followed, and its command run`() = onEdt {
        val features = open(target())

        follow(20)

        assertTrue($$"$resolveDocumentLink [5,[9,1]]" in asked, "$asked")
        // The id, then the arguments the link's query carried.
        assertEquals(
            $$"""$executeContributedCommand ["ext.show",1]""",
            asked.single { it.startsWith($$"$executeContributedCommand") })
        features.hostStopped()
    }

    @Test
    fun `links are asked for again after an edit, and the old list is released`() = onEdt {
        val features = open(target())
        val file = fixture.file.virtualFile
        features.documentLinks(file, fixture.editor.document.immutableCharSequence)

        WriteCommandAction.runWriteCommandAction(fixture.project) { fixture.editor.document.insertString(0, "  ") }
        features.documentChanged(file, HostedLanguageFeatures.Snapshot(fixture.editor.document.text, true))
        val moved = features.documentLinks(file, fixture.editor.document.immutableCharSequence)

        assertEquals(2, asked.count { it.startsWith($$"$provideDocumentLinks") }, "$asked")
        assertTrue($$"$releaseDocumentLinks [5,9]" in asked, "$asked")
        // The same answer, about the text as it now stands.
        assertEquals(4, moved.first().start)

        features.documentClosed(file)
        assertEquals(2, asked.count { it.startsWith($$"$releaseDocumentLinks") }, "$asked")
        features.hostStopped()
    }

    @Test
    fun `a provider for another language is not asked`() = onEdt {
        val features = open(target(), language = "other")

        assertNull(CodeBridgeDocumentLinkReferenceProvider().getImplicitReference(fixture.file, 6))
        assertTrue(asked.isEmpty(), "$asked")
        features.hostStopped()
    }

    private fun follow(offset: Int) {
        val reference = requireNotNull(CodeBridgeDocumentLinkReferenceProvider().getImplicitReference(fixture.file, offset))
        val symbol = reference.resolveReference().single() as CodeBridgeDocumentLinkSymbol
        symbol.target.navigate(true)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun fileOf(editor: Editor): VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)

    /** A file to link to, of two lines. */
    private fun target(): VirtualFile {
        val path = Files.createTempFile("codebridge-link-target", ".txt")
        Files.writeString(path, "one\ntwo three\n")
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    /**
     * Opens "see other.txt and docs", with a provider for [language] that links "other.txt" to
     * [target]'s second line and leaves "docs" to be resolved -- to a command.
     */
    private fun open(target: VirtualFile, language: String = "test"): HostedLanguageFeatures {
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.hostStopped()
        val path = Files.createTempFile("codebridge-links", ".txt")
        Files.writeString(path, "see other.txt and docs\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerDocumentLinkProvider",
            """[5,[{"language":"$language"}],true]"""
        )
        val linked = UriComponents.of(target.toNioPath()).copy(fragment = "L2,3").toJson()
        features.callHostForTest { _, method, args, _ ->
            asked.add("$method $args")
            
                when (method) {
                    $$"$provideDocumentLinks" -> """{"links":[
                        {"range":${range(5, 14)},"url":$linked,"tooltip":"Open other.txt","cacheId":[9,0]},
                        {"range":${range(19, 23)},"cacheId":[9,1]}
                    ],"cacheId":9}"""

                    $$"$resolveDocumentLink" ->
                        """{"range":${
                            range(
                                19,
                                23
                            )
                        },"url":{"scheme":"command","authority":"","path":"ext.show","query":"%5B1%5D","fragment":""}}"""

                    else -> ""
                }
            
        }
        return features
    }

    private fun range(startColumn: Int, endColumn: Int) =
        """{"startLineNumber":1,"startColumn":$startColumn,"endLineNumber":1,"endColumn":$endColumn}"""
}
