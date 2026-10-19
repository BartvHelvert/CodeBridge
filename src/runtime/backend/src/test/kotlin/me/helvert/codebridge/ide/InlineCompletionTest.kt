package me.helvert.codebridge.ide

import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeInlineCompletionProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An extension's inline completion as the IDE's ghost text: shown, accepted, dismissed. */
class InlineCompletionTest : EditorFixture() {

    /** What was asked of the host, by method, with its arguments. */
    private val calls = ConcurrentLinkedQueue<Pair<String, String>>()

    @AfterEach
    fun forgetSettings() = onEdt {
        fixture.project.service<HostedLanguageServers>().settingsForTest = null
        HostedLanguageFeatures.getInstance(fixture.project).hostStopped()
    }

    @Test
    fun `the proposal is drawn after the caret, typing over the bracket the editor closed`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            assertInlineElements {
                gray("\"hi\"")
                skip(")")
            }
        }
        // Asked as the user asking, at the caret.
        val (_, args) = calls.first { it.first == PROVIDE }
        assertTrue(""""triggerKind":1""" in args, args)
        assertTrue(""""lineNumber":1,"column":7""" in args, args)
    }

    @Test
    fun `accepting inserts the text and then runs the item's command, before the list is freed`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            insert()
            assertFileContent("print(\"hi\")<caret>\n")
        }
        awaitFreed()
        val methods = calls.map { it.first }
        val executed = methods.indexOf(EXECUTE)
        assertTrue(executed >= 0, "The command did not run: $methods")
        assertTrue(calls.elementAt(executed).second.contains("acme.accepted"))
        // The command is converted against the list, so the list outlives it.
        assertTrue(executed < methods.indexOf(FREE), "Freed before the command ran: $methods")
    }

    @Test
    fun `dismissing frees the list without running the command`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            escape()
            assertInlineHidden()
            assertFileContent("print(<caret>)\n")
        }
        awaitFreed()
        assertEquals("""[5,3,{"kind":"other"}]""", calls.first { it.first == FREE }.second)
        assertTrue(calls.none { it.first == EXECUTE })
    }

    @Test
    fun `nothing is asked with inlineSuggest off`() {
        open(settings = """{"editor.inlineSuggest.enabled": false}""")
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            assertInlineHidden()
        }
        assertTrue(calls.none { it.first == PROVIDE })
    }

    private fun awaitFreed() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (calls.none { it.first == FREE } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(calls.any { it.first == FREE }, "$FREE was not called: ${calls.map { it.first }}")
    }

    /** `print()`, the caret inside the brackets, and an extension that proposes `print("hi")`. */
    private fun open(settings: String = "{}") = onEdt {
        fixture.project.service<HostedLanguageServers>().settingsForTest = Json.parseToJsonElement(settings).jsonObject
        InlineCompletionProvider.EP_NAME.point.registerExtension(
            CodeBridgeInlineCompletionProvider(),
            fixture.testRootDisposable
        )
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.hostStopped()
        val path = Files.createTempFile("codebridge-inline", ".txt")
        Files.writeString(path, "print()\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        fixture.editor.caretModel.moveToOffset(6)
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerInlineCompletionsSupport",
            """[5,[{"language":"test"}],false,"acme.ghost","1.0.0",null,[],"Ghost",null,[]]"""
        )
        features.callHostForTest { _, method, args, _ ->
            calls += method to args
            
                if (method == PROVIDE) """{"pid":3,"languageId":"test","items":[{"insertText":"print(\"hi\")",
                    "range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":8},
                    "command":{"id":"acme.accepted","title":"","arguments":[42]},"pid":3,"idx":0}]}"""
                else ""
            
        }
    }

    private companion object {
        const val PROVIDE = $$"$provideInlineCompletions"
        const val FREE = $$"$freeInlineCompletionsList"
        const val EXECUTE = $$"$executeContributedCommand"
    }
}
