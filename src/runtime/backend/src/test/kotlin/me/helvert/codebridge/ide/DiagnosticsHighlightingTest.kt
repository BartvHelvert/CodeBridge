package me.helvert.codebridge.ide

import com.intellij.lang.LanguageExtensionPoint
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeDiagnosticsAnnotator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The last stretch, end to end: an extension pushes diagnostics and the editor shows them.
 *
 * Everything before this point was covered and three bugs still reached the editor, because each
 * one lived between "the protocol works" and "the user sees something" -- a URI whose drive letter
 * went missing, a file type detector that rejected every file, an identifier the host would not look
 * up. No protocol test could see any of them.
 *
 * So this starts from the wire payload the host actually sends and ends at `doHighlighting()`,
 * which is the daemon's own answer to what is drawn in this file.
 */
class DiagnosticsHighlightingTest : EditorFixture() {

    /**
     * A light fixture starts the platform, not this plugin, so the annotator has to be registered
     * by hand. `the plugin registers the annotator` covers the declaration itself, which this would
     * otherwise let anyone delete.
     */
    @BeforeEach
    fun registerAnnotator() = onEdt {
        // The three-argument form, because the extension machinery resolves the implementation
        // through the plugin descriptor's class loader and rejects a bean without one.
        val descriptor = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge"),
            CodeBridgeDiagnosticsAnnotator::class.java.classLoader,
        )
        // The empty key, as the descriptor registers it: that is `Language.ANY`'s id, and the EP
        // resolves through `allForLanguageOrAny`, so this reaches a JSON file as well as a text one.
        ANNOTATORS.point.registerExtension(
            LanguageExtensionPoint("", CodeBridgeDiagnosticsAnnotator::class.java.name, descriptor),
            fixture.testRootDisposable,
        )
    }

    @Test
    fun `unnecessary diagnostics use unused code styling`() = onEdt {
        open("unused variable")
        push("""[{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":7,"severity":1,"message":"$MESSAGE","tags":[1]}]""")

        val highlight = highlights().single()
        assertEquals(0, highlight.startOffset)
        assertEquals(6, highlight.endOffset)
        assertEquals(
            com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
            highlight.forcedTextAttributesKey,
        )

        push(MARKER)
        assertTrue(
            highlights().single().forcedTextAttributesKey !=
                    com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES
        )
    }

    @Test
    fun `the plugin registers the annotator`() {
        val descriptor = Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath()
        val text = Files.readString(descriptor)
        assertTrue(
            text.contains("""<annotator language="" implementationClass="me.helvert.codebridge.language.CodeBridgeDiagnosticsAnnotator"/>"""),
            "The annotator must stay declared for any of this to reach a real IDE",
        )
        // The empty key is `Language.ANY`. A claimed file does not always end up plain text: a
        // TextMate grammar gives it the `textmate` language, and an extension contributing `.json`
        // leaves it with the IDE's own. Both are root languages as far as a `TEXT` registration is
        // concerned, so it would never be asked. `LanguageAnnotators` resolves through
        // `allForLanguageOrAny`, which is what makes this one declaration enough.
    }

    @Test
    fun `a pushed diagnostic becomes a highlight`() = onEdt {
        open("ERROR replace this word to clear the diagnostic\nsecond line")

        push(MARKER)

        // Localises a failure: the service holding it but nothing drawing it is a different bug
        // from the payload never being understood.
        val stored = HostedLanguageFeatures.getInstance(fixture.project)
            .problemsFor(requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(opened)))
        assertEquals(1, stored.size, "The service did not file the diagnostic against this file")

        val highlight = highlights().single()
        // The marker named line 1, columns 1 to 6: the word ERROR.
        assertEquals(0, highlight.startOffset)
        assertEquals(5, highlight.endOffset)
        assertEquals("ERROR", fixture.editor.document.text.substring(highlight.startOffset, highlight.endOffset))
        assertTrue(
            highlight.description.contains("CodeBridge prototype"),
            "The source names the extension: ${highlight.description}"
        )
    }

    @Test
    fun `a file the IDE has its own language for is still annotated`() = onEdt {
        // Most extensions contribute an extension the IDE already knows -- `.json`, `.py`, `.md`.
        // Those files keep the IDE's own language instead of a CodeBridgeLanguageFileType, and JSON is a
        // root language, so an annotator registered for `TEXT` was never asked for one: the
        // diagnostic was filed against the file and nothing ever drew it. `language="any"` is what
        // fixes that, and this is the test that would catch it being narrowed again.
        open("""{"ERROR": 1}""", name = "example.json")
        assertEquals("JSON", fixture.file.language.id, "The premise is a language that is not TEXT")

        push(MARKER)

        assertEquals(1, highlights().size, "A diagnostic must be drawn whatever language the file has")
    }

    @Test
    fun `clearing a file removes what was drawn`() = onEdt {
        open("ERROR replace this word")
        push(MARKER)
        assertEquals(1, highlights().size)

        // Null markers mean "this file is clean now", which has to remove the annotation rather
        // than be read as "no news".
        push(null)
        assertEquals(0, highlights().size)
    }

    @Test
    fun `one collection reporting a clean file does not erase another's problems`() = onEdt {
        open("ERROR replace this word")

        // Two collections, both about this file, as a language client that pushes and pulls keeps.
        push(MARKER, owner = "rust-analyzer")
        push("[]", owner = "rustc")

        // The bug this pins: filing problems by resource alone let the second push replace the
        // first, so every time `cargo check` reported the file clean it wiped the live analysis and
        // the squiggle disappeared a moment after it appeared.
        assertEquals(1, highlights().size, "A clean report from one owner must not clear another's")

        // And withdrawing one collection leaves the other standing.
        push(null, owner = "rustc")
        assertEquals(1, highlights().size)
        push(null, owner = "rust-analyzer")
        assertEquals(0, highlights().size)
    }

    @Test
    fun `a push that changes nothing does not restart highlighting`() = onEdt {
        open("ERROR replace this word")
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        push(MARKER)
        assertEquals(1, highlights().size)
        val restarts = features.restartsForTest()

        // rust-analyzer repeats itself two or three times after every keystroke. With the restart no
        // longer waiting for the auto-reparse delay, each repeat would be a pass run mid-typing.
        push(MARKER)
        push("[]", owner = "rustc")
        push(null, owner = "never-reported")
        assertEquals(1, highlights().size)
        assertEquals(restarts, features.restartsForTest(), "Nothing changed, so nothing is redrawn")
    }

    @Test
    fun `a diagnostic for another file is not drawn here`() = onEdt {
        open("ERROR replace this word")
        push(MARKER, forFile = directory.resolve("elsewhere.txt"))
        assertEquals(0, highlights().size)
    }

    @Test
    fun `a stale position does not throw or escape the document`() = onEdt {
        open("short")
        // A line the document no longer has: pushed diagnostics outlive the edits that invalidate
        // them, and the daemon must not be handed an out-of-bounds range.
        push("""[{"startLineNumber":9,"startColumn":1,"endLineNumber":9,"endColumn":40,"severity":8,"message":"$MESSAGE"}]""")
        highlights().forEach {
            assertTrue(it.endOffset <= fixture.editor.document.textLength, "Range escaped the document: $it")
        }
    }

    /**
     * A real file, not the fixture's in-memory one.
     *
     * The URI is built from `VirtualFile.toNioPath`, which is unsupported on the temporary
     * filesystem a light fixture uses -- so an in-memory file would exercise a path the plugin never
     * takes, and the drive-letter bug would have hidden here too.
     */
    private fun open(text: String, name: String = "example.txt") {
        opened = directory.resolve(name).also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(opened))
        fixture.openFileInEditor(file)
    }

    /**
     * The payload spelled the way VS Code spells it, not the way the plugin does.
     *
     * Deliberately not built with `HostedLanguageFeatures.uriOf`: that is the function under test,
     * and using it here made the drive-letter bug invisible -- both sides were wrong in the same
     * way and agreed. VS Code sends `/C:/dir/file`, so that is what this sends.
     */
    private fun push(markers: String?, forFile: Path = opened, owner: String = "probe") {
        val path = "/" + forFile.toAbsolutePath().toString().replace(File.separatorChar, '/').trimStart('/')
        val payload = """["$owner",[[{"scheme":"file","authority":"","path":"$path","query":"","fragment":""},""" +
                (markers ?: "null") + "]]]"
        HostedLanguageFeatures.getInstance(fixture.project).handle("MainThreadDiagnostics", $$"$changeMany", payload)
    }

    /**
     * Redraws are coalesced, so the deferred daemon restart has to land before the pass runs --
     * otherwise it arrives in the middle of this one, which the fixture rejects.
     */
    private fun highlights(): List<com.intellij.codeInsight.daemon.impl.HighlightInfo> {
        HostedLanguageFeatures.getInstance(fixture.project).awaitRepaintsForTest()
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        return fixture.doHighlighting().filter { it.description?.contains(MESSAGE) == true }
    }

    private lateinit var opened: Path

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-highlighting").toRealPath() }

    private companion object {
        val ANNOTATORS = ExtensionPointName<LanguageExtensionPoint<Annotator>>("com.intellij.annotator")

        const val MESSAGE = "Replace ERROR to clear this diagnostic"
        const val MARKER = """[{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":6,""" +
                """"severity":8,"message":"$MESSAGE","source":"CodeBridge prototype"}]"""
    }
}
