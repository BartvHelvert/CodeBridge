package me.helvert.codebridge.workbench

import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a webview's page may read: files under the roots its extension allowed, and nothing else. */
class WebviewResourcesTest {
    @TempDir lateinit var temp: Path

    /** [file] as `asWebviewUri` spells it. */
    private fun address(file: Path) = "https://file+.vscode-resource.vscode-cdn.net" + URI(UriComponents.of(file).uri).rawPath

    @Test
    fun `reads a file under a root, with its media type`() {
        val media = Files.createDirectories(temp.resolve("ext/media dir"))
        Files.writeString(media.resolve("main.js"), "go()")
        val read = HostedWebviews.read(address(media.resolve("main.js")) + "?v=2", listOf(UriComponents.of(temp.resolve("ext"))))
        assertEquals("go()", read?.bytes?.decodeToString())
        assertEquals("text/javascript", read?.mimeType)
    }

    @Test
    fun `refuses a file outside every root, however it is spelled`() {
        val root = Files.createDirectories(temp.resolve("ext"))
        Files.writeString(temp.resolve("secret.txt"), "no")
        val roots = listOf(UriComponents.of(root))
        assertNull(HostedWebviews.read(address(temp.resolve("secret.txt")), roots))
        assertNull(HostedWebviews.read(address(root) + "/../secret.txt", roots))
        assertNull(HostedWebviews.read(address(temp.resolve("secret.txt")), emptyList()))
        assertNull(HostedWebviews.read("https://example.com" + URI(UriComponents.of(temp.resolve("secret.txt")).uri).rawPath, listOf(UriComponents.of(temp))))
    }

    @Test
    fun `refuses a folder and a file that is not there`() {
        val root = Files.createDirectories(temp.resolve("ext"))
        assertNull(HostedWebviews.read(address(root), listOf(UriComponents.of(temp))))
        assertNull(HostedWebviews.read(address(root.resolve("gone.css")), listOf(UriComponents.of(temp))))
    }
}
