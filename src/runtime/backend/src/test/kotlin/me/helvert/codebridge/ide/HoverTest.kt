package me.helvert.codebridge.ide

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiDocumentManager
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeHoverLinkHandler
import me.helvert.codebridge.language.CodeBridgeHoverTargetProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quick Documentation, from the caret to the reply and back.
 *
 * The round trip to the extension needs a live host, so that half is covered by
 * `LanguageFeaturesIntegrationTest` against a real one. What is covered here is everything on the
 * IntelliJ side of that call: whether a target is offered at all, and the caret offset becoming
 * the 1-based position VS Code expects. Reading the reply itself is the protocol module's, and is
 * tested there.
 */
class HoverTest : EditorFixture() {

    @Test
    fun `offers nothing when no extension answers hovers`() = onEdt {
        open("some text")
        // The common case in any project without a VS Code language extension: Quick Documentation
        // must not be intercepted at all.
        assertTrue(targets().isEmpty())
    }

    @Test
    fun `offers a target once an extension registers a hover provider`() = onEdt {
        open("some text")
        register(handle = 1)
        assertEquals(1, targets().size)
        features().handle("MainThreadLanguageFeatures", $$"$unregisterHoverProvider", "[1]")
    }

    @Test
    fun `offers nothing in a file no provider claims`() = onEdt {
        open("some text")
        // A provider for another language, as GitLens registers for its own schemes: IntelliJ's
        // documentation is left alone rather than joined by an empty hover.
        register(handle = 2, language = "rust")
        assertTrue(targets().isEmpty())
        features().handle("MainThreadLanguageFeatures", $$"$unregisterHoverProvider", "[2]")
    }

    @Test
    fun `offers nothing in a file that is not mirrored`() = onEdt {
        open("some text", mirrored = false)
        register(handle = 3)
        assertTrue(targets().isEmpty())
        features().handle("MainThreadLanguageFeatures", $$"$unregisterHoverProvider", "[3]")
    }

    @Test
    fun `stops offering when the provider is unregistered`() = onEdt {
        open("some text")
        register(handle = 7)
        assertEquals(1, targets().size)

        features().handle("MainThreadLanguageFeatures", $$"$unregisterHoverProvider", "[7]")
        assertTrue(targets().isEmpty())
    }

    @Test
    fun `claims a hover's command links and no other`() = onEdt {
        open("some text")
        register(handle = 1)
        val hover = targets().single()
        val handler = CodeBridgeHoverLinkHandler()
        // Claimed, or the platform hands `command:` to the browser.
        // As a monolith passes it, and as a split-mode frontend forwards it, prefix removed.
        assertNotNull(handler.resolveLink(hover, "psi_element://command:ext.run?0"))
        assertNotNull(handler.resolveLink(hover, "command:ext.run?0"))
        // Left to the platform, which opens addresses itself.
        assertNull(handler.resolveLink(hover, "https://example.com"))
        // The light project outlives the test; a provider left registered leaks into the next one.
        features().handle("MainThreadLanguageFeatures", $$"$unregisterHoverProvider", "[1]")
    }

    /** Documentation targets for the caret at the start of line 2, where the offsets are non-zero. */
    private fun targets(): List<DocumentationTarget> {
        val psi = requireNotNull(PsiDocumentManager.getInstance(fixture.project).getPsiFile(fixture.editor.document))
        val offset = fixture.editor.document.textLength
        return CodeBridgeHoverTargetProvider().documentationTargets(psi, offset)
    }

    private fun register(handle: Int, language: String = "plaintext") {
        features().handle(
            "MainThreadLanguageFeatures",
            $$"$registerHoverProvider",
            """[$handle,[{"language":"$language"}]]"""
        )
    }

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    /** Opens the file, mirrored as plain text unless [mirrored] is false: only a mirrored file has hovers. */
    @Suppress("SameParameterValue")
    private fun open(text: String, mirrored: Boolean = true) {
        val path: Path = directory.resolve("hover.txt").also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        if (!mirrored) return
        features().mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features().documentOpened(file, "plaintext", HostedLanguageFeatures.Snapshot(text, false))
    }

    /** The light project outlives the test; a document left mirrored would be mirrored in the next. */
    @AfterEach
    fun close() = onEdt {
        LocalFileSystem.getInstance().findFileByNioFile(directory.resolve("hover.txt"))?.let(features()::documentClosed)
    }

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-hover") }
}
