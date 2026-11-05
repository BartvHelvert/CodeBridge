package me.helvert.codebridge.ide

import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeInlineCompletionLists
import me.helvert.codebridge.language.CodeBridgeInlineCompletionProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An extension's inline completion as the IDE's ghost text: shown, accepted, dismissed. */
class InlineCompletionTest : EditorFixture() {

    /** What was asked of the host, by method, with its arguments. */
    private val calls = ConcurrentLinkedQueue<Pair<String, String>>()

    /** The id of the next list the host answers with: each request's list is told apart by it. */
    private val lists = AtomicInteger(FIRST_LIST)

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
        assertTrue(executed < calls.indexOfFirst(::freesFirst), "Freed before the command ran: $methods")
    }

    @Test
    fun `an insertion the backend saw only as an edit runs the command once the client says it took the item`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            // A split-mode client's insertion, arriving before the Tab it forwards: the session
            // ends as the text changed under it, not as accepted.
            withWriteAction { fixture.editor.document.setText(ACCEPTED) }
            assertInlineHidden()
        }
        assertTrue(calls.none { it.first == EXECUTE })
        runBlocking { CodeBridgeInlineCompletionLists.getInstance(fixture.project).accepted(ACCEPTED.length, ACCEPTED.hashCode()) }
        awaitFreed()
        val methods = calls.map { it.first }
        val executed = methods.indexOf(EXECUTE)
        assertTrue(executed >= 0, "The command did not run: $methods")
        assertTrue(executed < calls.indexOfFirst(::freesFirst), "Freed before the command ran: $methods")
    }

    @Test
    fun `the client saying it took an item the backend accepted runs nothing more`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            insert()
        }
        awaitFreed()
        runBlocking { CodeBridgeInlineCompletionLists.getInstance(fixture.project).accepted(ACCEPTED.length, ACCEPTED.hashCode()) }
        assertEquals(1, calls.count { it.first == EXECUTE }, calls.map { it.first }.toString())
    }

    @Test
    fun `a remote backend selection waits for the inserting client's acknowledgement`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            useClientInsertion()
            insert()
        }
        assertTrue(calls.none { it.first == EXECUTE }, calls.map { it.first }.toString())
        runBlocking { CodeBridgeInlineCompletionLists.getInstance(fixture.project).accepted(ACCEPTED.length, ACCEPTED.hashCode()) }
        awaitFreed()
        assertEquals(1, calls.count { it.first == EXECUTE }, calls.map { it.first }.toString())
        runBlocking { CodeBridgeInlineCompletionLists.getInstance(fixture.project).accepted(ACCEPTED.length, ACCEPTED.hashCode()) }
        assertEquals(1, calls.count { it.first == EXECUTE }, calls.map { it.first }.toString())
    }

    @Test
    fun `a client acknowledgement overtaking the forwarded Tab runs its command once`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            useClientInsertion()
            // An earlier request offered the same text before the client restarted its session.
            escape()
            callInlineCompletion()
            delay()
            useClientInsertion()
            coroutineScope {
                // Consume the older hidden offer before the forwarded Tab selects the current
                // backend session. Both notifications describe the same client insertion.
                val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
                    CodeBridgeInlineCompletionLists.getInstance(fixture.project)
                        .accepted(ACCEPTED.length, ACCEPTED.hashCode())
                }
                insert()
                acknowledgement.await()
            }
        }
        awaitFreed()
        assertEquals(1, calls.count { it.first == EXECUTE }, calls.map { it.first }.toString())
    }

    /** The light fixture has no remote session; retain its real offer as a remote request does. */
    private fun useClientInsertion() {
        val session = requireNotNull(InlineCompletionSession.getOrNull(fixture.editor))
        val offered = requireNotNull(
            session.context.getUserData(CodeBridgeInlineCompletionProvider.OFFERED)
                ?: session.capture()?.activeVariant?.data?.getUserData(CodeBridgeInlineCompletionProvider.OFFERED)
        )
        ClientId.withClientId(ClientId("inline-test-client")) {
            CodeBridgeInlineCompletionLists.getInstance(fixture.project)
                .hold(fixture.editor, emptyList(), listOf(offered))
        }
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
    fun `a dismissed item is forgotten with its editor, so the client taking the same text runs nothing`() {
        open()
        fixture.testInlineCompletion {
            callInlineCompletion()
            delay()
            escape()
            assertInlineHidden()
        }
        assertTrue(calls.none { it.first == FREE })
        onEdt { FileEditorManager.getInstance(fixture.project).closeFile(requireNotNull(fixture.editor.virtualFile)) }
        // Freed as the editor goes, not when the item would have been let go anyway.
        assertTrue(calls.any(::freesFirst), "$FREE was not called: ${calls.map { it.first }}")
        runBlocking { CodeBridgeInlineCompletionLists.getInstance(fixture.project).accepted(ACCEPTED.length, ACCEPTED.hashCode()) }
        assertTrue(calls.none { it.first == EXECUTE }, calls.map { it.first }.toString())
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

    /**
     * Waits for the first list to be freed. An insertion asks again, and that request's list can be
     * freed first, before the accepted item's command has run.
     */
    private fun awaitFreed() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (calls.none(::freesFirst) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(calls.any(::freesFirst), "$FREE was not called: ${calls.map { it.first }}")
    }

    private fun freesFirst(call: Pair<String, String>) = call.first == FREE && call.second.startsWith("[5,$FIRST_LIST,")

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
            
                if (method == PROVIDE) {
                    val pid = lists.getAndIncrement()
                    """{"pid":$pid,"languageId":"test","items":[{"insertText":"print(\"hi\")",
                    "range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":8},
                    "command":{"id":"acme.accepted","title":"","arguments":[42]},"pid":$pid,"idx":0}]}"""
                } else ""
            
        }
    }

    private companion object {
        /** The document with the proposal accepted. */
        const val ACCEPTED = "print(\"hi\")\n"
        const val FIRST_LIST = 3
        const val PROVIDE = $$"$provideInlineCompletions"
        const val FREE = $$"$freeInlineCompletionsList"
        const val EXECUTE = $$"$executeContributedCommand"
    }
}
