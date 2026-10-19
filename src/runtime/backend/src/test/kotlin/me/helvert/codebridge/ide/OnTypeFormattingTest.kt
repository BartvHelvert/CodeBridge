package me.helvert.codebridge.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeOnTypeFormatting
import me.helvert.codebridge.started
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnTypeFormattingTest : EditorFixture() {

    private val asked = ConcurrentLinkedQueue<String>()

    @AfterEach
    fun forgetSettings() = onEdt { fixture.project.service<HostedLanguageServers>().settingsForTest = null }

    @Test
    fun `a trigger character applies the formatter's edits`() = onEdt {
        val features = open(settings = """{"editor.formatOnType": true}""")
        answer("""[{"range":${range(1, 8, 1, 8)},"text":";"}]""")

        val applied = type("=")

        assertTrue(applied)
        assertEquals("let x =;", fixture.editor.document.text)
        val sent = asked.single()
        assertTrue(sent.startsWith($$"$provideOnTypeFormattingEdits [6,{"), sent)
        assertTrue(sent.contains(""""lineNumber":1,"column":8},"=",{"tabSize":"""), sent)
        features.hostStopped()
    }

    @Test
    fun `enter is asked about as a newline, from the start of the new line`() = onEdt {
        val features = open(settings = """{"[test]": {"editor.formatOnType": true}}""", triggers = """["\n"]""")
        answer("[]")

        assertFalse(type("\n"))

        assertTrue(asked.single().contains(""""lineNumber":2,"column":1},"\n","""), asked.single())
        features.hostStopped()
    }

    @Test
    fun `nothing is asked with formatOnType off, as it is by default`() = onEdt {
        val features = open(settings = """{"[other]": {"editor.formatOnType": true}}""")
        answer("[]")

        assertFalse(type("="))

        assertTrue(asked.isEmpty(), "$asked")
        features.hostStopped()
    }

    @Test
    fun `a character the formatter does not name is not asked about`() = onEdt {
        val features = open(settings = """{"editor.formatOnType": true}""")
        answer("[]")

        assertFalse(type("x"))

        assertTrue(asked.isEmpty(), "$asked")
        features.hostStopped()
    }

    @Test
    fun `edits that arrive after another edit are dropped`() = onEdt {
        val features = open(settings = """{"editor.formatOnType": true}""")
        val late = CompletableDeferred<String>()
        answer(late)
        val document = fixture.editor.document

        val applied = start("=")
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(document.textLength, " 1") }
        late.complete("""[{"range":${range(1, 8, 1, 8)},"text":";"}]""")

        assertFalse(await(applied))
        assertEquals("let x = 1", document.text)
        features.hostStopped()
    }

    @Test
    fun `a language block wins over the setting itself`() {
        val settings = Json.parseToJsonElement(
            """{"editor.formatOnType": false, "[rust][toml]": {"editor.formatOnType": true}, "[go]": {}}"""
        ).jsonObject
        assertEquals("true", HostedLanguageServers.languageSetting(settings, "editor.formatOnType", "rust").toString())
        assertEquals("true", HostedLanguageServers.languageSetting(settings, "editor.formatOnType", "toml").toString())
        assertEquals("false", HostedLanguageServers.languageSetting(settings, "editor.formatOnType", "go").toString())
        assertEquals("false", HostedLanguageServers.languageSetting(settings, "editor.formatOnType", null).toString())
        assertNull(HostedLanguageServers.languageSetting(settings, "editor.tabSize", "rust"))
    }

    /** Types [ch] at the end of the document, mirrors it, and waits for the formatter's edits. */
    private fun type(ch: String): Boolean = await(start(ch))

    private fun start(ch: String): CompletableFuture<Boolean> {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(document.textLength, ch) }
        fixture.editor.caretModel.moveToOffset(document.textLength)
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.documentChanged(fixture.file.virtualFile, HostedLanguageFeatures.Snapshot(document.text, true))
        val typed = CodeBridgeOnTypeFormatting.typed(fixture.project, fixture.editor, fixture.file, ch)
        return started { typed.await() }
    }

    /** Waits on the editor thread, where the edits are applied. */
    private fun await(applied: CompletableFuture<Boolean>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!applied.isDone && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return applied.getNow(null) ?: error("No answer in time")
    }

    private fun open(settings: String, triggers: String = """["=",";"]"""): HostedLanguageFeatures {
        fixture.project.service<HostedLanguageServers>().settingsForTest = Json.parseToJsonElement(settings).jsonObject
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.hostStopped()
        val path = Files.createTempFile("codebridge-on-type", ".txt")
        Files.writeString(path, "let x ")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerOnTypeFormattingSupport",
            """[6,[{"language":"test"}],$triggers,"ext"]"""
        )
        return features
    }

    private fun answer(reply: String) = answer(CompletableDeferred(reply))

    private fun answer(reply: Deferred<String>) {
        HostedLanguageFeatures.getInstance(fixture.project).callHostForTest { _, method, args, _ ->
            asked.add("$method $args")
            reply.await()
        }
    }

    @Suppress("SameParameterValue") // A builder: the call reads as what it makes.
    private fun range(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        """{"startLineNumber":$startLine,"startColumn":$startColumn,"endLineNumber":$endLine,"endColumn":$endColumn}"""
}
