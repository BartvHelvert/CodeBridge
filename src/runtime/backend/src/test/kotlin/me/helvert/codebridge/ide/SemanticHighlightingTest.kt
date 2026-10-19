package me.helvert.codebridge.ide

import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.UIUtil
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The colouring a grammar cannot do, from the wire buffer to what the editor draws.
 *
 * The protocol tests pin the decoding and the arithmetic. What only goes wrong here is everything
 * about *when*: an answer has to be asked for as the document changes, the 0-based positions have to
 * land on the right characters of a real document, and -- the expensive lesson -- none of it may
 * wait on a highlighting pass. Colour used to be drawn by an annotator, so every answer restarted
 * the daemon: 300ms before the pass even began, and a restart cancels the pass that is running,
 * taking its diagnostics with it. Colour is now drawn straight onto the document, and these tests
 * read it from there without ever running a pass.
 */
class SemanticHighlightingTest : EditorFixture() {

    @BeforeEach
    fun reset() = onEdt {
        // A light fixture reuses one project for the whole class, and this service belongs to the
        // project. Without the reset each test inherits the last one's providers, mirrored
        // documents and cached colouring -- and those files then get asked about again, by a
        // provider the current test registered, against a responder it never installed.
        HostedLanguageFeatures.getInstance(fixture.project).hostStopped()
    }

    @Test
    fun `an answer is drawn without a highlighting pass, and a pass leaves it alone`() = onEdt {
        open(SOURCE)
        mirror()
        answer(0, 4, 5, PARAMETER, 0)
        register()
        settle()
        // Drawn with no pass run at all. The pass is what cost the time: a restart waits for the
        // auto-reparse delay and then runs every pass over the file, and a second restart -- the
        // diagnostics that arrive after every edit -- cancels the first and starts the wait again.
        assertEquals(1, semantic().size, "Colour has to be on the document before any pass runs")

        // Nor does a pass ask the extension anything, or take the colour off again: the daemon
        // removes only what it drew itself.
        features().callHostForBytesTest { _, method, _, _ ->
            fail("The highlighting pass asked the extension for $method")
        }
        fixture.doHighlighting()
        assertEquals("alpha", text(semantic().single()))
    }

    @Test
    fun `a token lands on the characters the host named`() = onEdt {
        open(SOURCE)
        // `alpha` at line 0 character 4, then `compute` eight characters further along the same
        // line -- the second start is a delta from the first, not an absolute column.
        answer(
            0, 4, 5, PARAMETER, 0,
            0, 8, 7, METHOD, STATIC,
        )
        mirror()
        register()
        settle()

        val coloured = semantic()
        assertEquals(2, coloured.size, "Both tokens should be drawn: $coloured")
        assertEquals("alpha", text(coloured[0]))
        assertEquals("CODEBRIDGE_SEMANTIC_PARAMETER", key(coloured[0]))
        assertEquals("compute", text(coloured[1]))
        // The modifier picks a different member of the family the type chose.
        assertEquals("CODEBRIDGE_SEMANTIC_STATIC_METHOD", key(coloured[1]))
    }

    @Test
    fun `a modifier can add to the colouring instead of replacing it`() = onEdt {
        open(SOURCE)
        mirror()
        answer(0, 12, 7, METHOD, DEPRECATED)
        register()
        settle()

        val coloured = semantic()
        // Two annotations over one span: the method's own colour, and a strikethrough that has to
        // reach it rather than replace it.
        assertEquals(2, coloured.size, "Deprecation should layer over the colour, not replace it: $coloured")
        assertTrue(coloured.all { text(it) == "compute" })
        assertEquals(
            setOf("CODEBRIDGE_SEMANTIC_INSTANCE_METHOD", "CODEBRIDGE_SEMANTIC_DEPRECATED"),
            coloured.mapNotNull { key(it) }.toSet(),
        )
    }

    @Test
    fun `a type the mapping has no opinion about keeps the colour it already had`() = onEdt {
        open(SOURCE)
        mirror()
        answer(0, 4, 5, 2, 0)
        register(types = listOf("parameter", "method", "houseStyle"))
        settle()

        // Not an error, and not a default colour painted over the grammar's: nothing at all, so an
        // unfamiliar vocabulary degrades to the highlighting that was already there.
        assertTrue(semantic().isEmpty(), "An unknown token type must not be drawn")
    }

    @Test
    fun `an edit asks again, and the new answer is drawn`() = onEdt {
        open(SOURCE)
        mirror()
        answer(0, 4, 5, PARAMETER, 0)
        register()
        settle()
        assertEquals("alpha", text(semantic().single()))

        // The push model's whole point: the colouring follows the document, without the pass ever
        // being the thing that asks.
        answer(0, 4, 5, METHOD, 0)
        features().documentChanged(
            fixture.file.virtualFile,
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, true),
        )
        settle()

        assertEquals("CODEBRIDGE_SEMANTIC_INSTANCE_METHOD", key(semantic().single()))
    }

    @Test
    fun `colour stays on its word while typing, before any new answer`() = onEdt {
        open(SOURCE)
        mirror()
        answer(0, 4, 5, PARAMETER, 0)
        register()
        settle()

        // A new line above: every word moves down a line. The highlighter moves with it, so the
        // colour is right on the very next frame rather than one round trip later.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.project) {
            fixture.editor.document.insertString(0, "\n")
        }
        assertEquals("alpha", text(semantic().single()))
    }

    @Test
    fun `a file the host is not mirroring is never asked about`() = onEdt {
        open(SOURCE)
        // Mirrored documents are the ones an extension's own language client has been told about;
        // a provider cannot answer for anything else.
        features().callHostForBytesTest { _, _, _, _ ->
            fail("The host must not be asked about a file it holds no copy of")
        }
        register()
        settle()
        assertTrue(semantic().isEmpty())
    }

    @Test
    fun `a range provider answers for the whole document when nothing else will`() = onEdt {
        open(SOURCE)
        // A range provider answers about exactly the range it is given, so the request has to name
        // the whole document -- to the last line, which the fixture's trailing newline creates.
        val lastLine = fixture.editor.document.lineCount
        asked = null
        features().callHostForBytesTest { _, method, args, cancellable ->
            asked = method
            // Not because anything here cancels: the method's last parameter is a CancellationToken
            // the host appends itself, and it only does that for a cancellable request.
            assertTrue(cancellable, "The host appends the token only to a cancellable request")
            assertTrue(args.contains(""""endLineNumber":$lastLine"""), args)
            tokens(0, 4, 5, PARAMETER, 0)
        }
        mirror()
        features().handle(
            "MainThreadLanguageFeatures", $$"$registerDocumentRangeSemanticTokensProvider", registration()
        )
        settle()

        assertEquals("alpha", text(semantic().single()))
        assertEquals($$"$provideDocumentRangeSemanticTokens", asked)
    }

    @Test
    fun `a provider that goes away takes its colouring with it`() = onEdt {
        open(SOURCE)
        mirror()
        answer(0, 4, 5, PARAMETER, 0)
        register()
        settle()
        assertEquals(1, semantic().size)

        features().handle(
            "MainThreadLanguageFeatures", $$"$unregisterDocumentSemanticTokensProvider", "[$HANDLE]"
        )
        asked = null

        // Colour left behind by a provider nothing stands behind any more is worse than none.
        assertTrue(semantic().isEmpty())
        assertNull(asked, "Nothing should be asked once the provider is gone")
    }

    @Test
    fun `the next request asks for the difference, and the host is told when to let go`() = onEdt {
        open(SOURCE)
        val released = mutableListOf<String>()
        features().callHostForTest { _, method, args, _ ->
            if (method == $$"$releaseDocumentSemanticTokens") released += args
            ""
        }
        val requests = mutableListOf<String>()
        features().callHostForBytesTest { _, _, args, _ ->
            requests += args
            
                // A non-zero result id means the host kept this answer to diff the next request
                // against. The second answer is that diff: the first token's length, 5, becomes 3.
                if (requests.size == 1) tokens(resultId = 17, data = intArrayOf(0, 4, 5, PARAMETER, 0))
                else delta(resultId = 18, start = 2, deleteCount = 1, 3)
            
        }
        mirror()
        register()
        settle()
        assertEquals("alpha", text(semantic().single()))

        features().handle("MainThreadLanguageFeatures", $$"$emitDocumentSemanticTokensEvent", "[1]")
        settle()

        assertTrue(requests.last().endsWith(",17]"), "The second request names the answer it diffs: ${requests.last()}")
        assertEquals("alp", text(semantic().single()), "The delta is applied to the answer it was against")
        // Held, not released: the host drops an answer once it has been diffed against, and the
        // answer that replaced it is the base for the next one.
        assertEquals(emptyList(), released)

        features().documentClosed(fixture.file.virtualFile)
        // Nothing will diff against it any more, so an unreleased id would be a leak in the host.
        assertEquals(listOf("[$HANDLE,18]"), released)
    }

    // --- fixture ----------------------------------------------------------------------------

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    /** A real file: the URI is built from `toNioPath`, which the fixture's own filesystem refuses. */
    private fun open(text: String, name: String = "example.txt") {
        opened = directory.resolve(name).also { it.writeText(text) }
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(opened)))
    }

    /**
     * Puts a document mirror in place with no host behind it, as the feature tests do.
     *
     * `hostStopped` first, because the service is per project and the fixture's project outlives
     * nothing here -- but the registrations and cached colouring of a previous test would.
     */
    private fun mirror() {
        val features = features()
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false),
        )
    }

    private fun register(types: List<String> = TYPES) {
        features().handle(
            "MainThreadLanguageFeatures",
            $$"$registerDocumentSemanticTokensProvider",
            registration(types)
        )
    }

    private fun registration(types: List<String> = TYPES): String =
        """[$HANDLE,[{"language":"test"}],{"tokenTypes":${types.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                """"tokenModifiers":["static","deprecated"]},null]"""

    /** Waits for the debounce and the request behind it, the way idle time would in an IDE. */
    private fun settle() {
        assertNull(features().awaitSemanticTokensForTest(), "The semantic token request never settled")
    }

    /** What the provider replies with, and a note of whether it was asked at all. */
    private fun answer(vararg data: Int) = answer(resultId = 0, data = data)

    @Suppress("SameParameterValue")
    private fun answer(resultId: Int, data: IntArray) {
        asked = null
        features().callHostForBytesTest { _, method, args, _ ->
            asked = method
            assertTrue(args.endsWith(",0]"), "The request must ask for the whole file: $args")
            tokens(resultId = resultId, data = data)
        }
    }

    @Suppress("SameParameterValue")
    private fun tokens(vararg data: Int): ByteArray = tokens(resultId = 0, data = data)

    /** The `VSBuffer` layout: result id, `Full`, data length, then five words per token. */
    private fun tokens(resultId: Int, data: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate((3 + data.size) * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(resultId).putInt(1).putInt(data.size)
        data.forEach(buffer::putInt)
        return buffer.array()
    }

    /** One edit as a `Delta` reply carries it: where in the previous numbers, how many go, what comes. */
    @Suppress("SameParameterValue")
    private fun delta(resultId: Int, start: Int, deleteCount: Int, vararg data: Int): ByteArray {
        val buffer = ByteBuffer.allocate((6 + data.size) * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(resultId).putInt(2).putInt(1).putInt(start).putInt(deleteCount).putInt(data.size)
        data.forEach(buffer::putInt)
        return buffer.array()
    }

    /**
     * The colour highlighters on the document, in document order.
     *
     * No highlighting pass is run: none is involved in drawing them, and that is what these tests
     * hold. The queue is drained first, because an answer is drawn on the EDT, and in a fixture every
     * thread that would have run it is this one.
     */
    private fun semantic(): List<RangeHighlighter> {
        UIUtil.dispatchAllInvocationEvents()
        return features().drawnColoursForTest(fixture.file.virtualFile).sortedBy { it.startOffset }
    }

    private fun key(highlighter: RangeHighlighter): String? = highlighter.textAttributesKey?.externalName

    private fun text(highlighter: RangeHighlighter): String =
        fixture.editor.document.text.substring(highlighter.startOffset, highlighter.endOffset)

    @Volatile
    private var asked: String? = null

    private lateinit var opened: Path

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-semantic") }

    private companion object {
        //          0123456789...
        const val SOURCE = "let alpha = compute()\nlet beta = alpha + alpha\n"

        const val HANDLE = 7

        val TYPES = listOf("parameter", "method")
        const val PARAMETER = 0
        const val METHOD = 1

        /** Bits over the registered modifier list, `["static", "deprecated"]`. */
        const val STATIC = 0b01
        const val DEPRECATED = 0b10
    }
}
