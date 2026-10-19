package me.helvert.codebridge.exthost.protocol.payload

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.nio.file.Path
import kotlin.test.*

/**
 * How a resource is spelled, which is the step in this protocol that has broken most often.
 *
 * A URI that lost its drive letter meant diagnostics were filed under a key nothing could look up,
 * and the failure was invisible: the problem arrived, was stored, and the annotator asked a
 * different question. These are the cheapest tests in the project and they cover that.
 */
class UriComponentsTest {

    @Test
    fun `a windows file uri keeps its drive`() {
        assumeTrue(WINDOWS, "A drive letter is a path only on Windows")
        val uri = UriComponents.of(Path.of("C:\\Projects\\CodeBridge\\example.codebridgebundled")).uri
        assertEquals("file:///C:/Projects/CodeBridge/example.codebridgebundled", uri)
    }

    @Test
    fun `the drive survives the round trip a diagnostic makes`() {
        // What the workbench sends the host, and what comes back on `$changeMany`, must agree. They
        // did not: IntelliJ's own `file://C:/x` spelling parses with `C:` as the URI authority, so
        // the path lost the drive and the two keys could never match.
        val sent = UriComponents.of(Path.of("C:\\Projects\\CodeBridge\\example.codebridgebundled")).uri
        val returned = UriComponents.of(sent).uri
        assertEquals(UriComponents.key(sent), UriComponents.key(returned))
        assertTrue(returned.contains("C:"), "The returned URI must still name the drive: $returned")
    }

    @Test
    fun `intellij's own url spelling is not interchangeable with a file uri`() {
        // Pins the trap rather than the fix: these two spell the same file and do not compare equal,
        // which is why nothing may key off `VirtualFile.url`.
        val intellij = "file://C:/Projects/CodeBridge/example.codebridgebundled"
        val standard = UriComponents.of(Path.of("C:\\Projects\\CodeBridge\\example.codebridgebundled")).uri
        assertNotEquals(UriComponents.key(intellij), UriComponents.key(standard))
    }

    @Test
    fun `a drive letter is compared case-insensitively`() {
        // The host matches documents by string, so these two are one file to the filesystem and
        // would otherwise be two documents to it.
        assertEquals(
            UriComponents.key("file:///C:/Projects/example.codebridgebundled"),
            UriComponents.key("file:///c:/Projects/example.codebridgebundled"),
        )
    }

    @Test
    fun `round trips through the wire shape`() {
        val original = UriComponents.of(Path.of("C:\\Projects\\example.codebridgebundled"))
        assertEquals(original, UriComponents.from(original.toJson()))
    }

    @Test
    fun `reads back as the path it names`() {
        val path = Path.of(System.getProperty("java.io.tmpdir"), "example.codebridgebundled").toAbsolutePath()
        assertEquals(path, UriComponents.of(path).toPath())
    }

    @Test
    fun `a file name with a percent sign, a hash and an accent survives the trip to a path and to a uri`() {
        val path = Path.of(System.getProperty("java.io.tmpdir"), "100% # café.bin").toAbsolutePath()
        val resource = UriComponents.of(path)
        assertEquals(path, resource.toPath())
        assertEquals(path, Path.of(URI(resource.uri)))
        assertEquals(resource, UriComponents.of(resource.uri))
        assertEquals(UriComponents.key(path), UriComponents.key(resource.uri))
    }

    @Test
    fun `a resource without a path is not one`() {
        assertNull(UriComponents.from(kotlinx.serialization.json.buildJsonObject { }))
    }

    @Test
    fun `a document an extension provides keeps its uri through the round trip`() {
        // Java's name for a class in a library jar, as its language server gave one for a definition.
        val jdt = "jdt://contents/junit-jupiter-api-5.11.4.jar/org.junit.jupiter.api/Test.java" +
                "?=java-sandbox/C:%5C/Users%5C/u%5C/.gradle%5C/junit-jupiter-api-5.11.4.jar=/gradle_used_by_scope=/test=/=/test"
        val resource = UriComponents.of(jdt)

        assertEquals("jdt", resource.scheme)
        assertEquals("/junit-jupiter-api-5.11.4.jar/org.junit.jupiter.api/Test.java", resource.path)
        assertEquals(jdt, resource.uri)
        assertEquals(resource, UriComponents.from(resource.toJson()))
        assertEquals(jdt, UriComponents.key(jdt))
    }

    @Test
    fun `refuses what is not a uri`() {
        val failure = runCatching { UriComponents.of("no-scheme-here") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "got $failure")
    }

    @Test
    fun `an untitled buffer is named by its path alone`() {
        // VS Code's own shape for `untitled:Untitled-1`: no authority, and a path with no slash.
        val untitled = UriComponents.of("untitled:Untitled-1")
        assertEquals(UriComponents("untitled", "", "Untitled-1", "", ""), untitled)
        assertEquals("untitled:Untitled-1", untitled.uri)
        assertEquals(untitled, UriComponents.from(untitled.toJson()))
        assertEquals("untitled:Untitled-1", UriComponents.key("untitled:Untitled-1"))
    }

    @Test
    fun `a directory has no trailing slash, as vs code writes it`() {
        // rust-analyzer swaps the folder's path for `${'$'}{workspaceFolder}`; a trailing slash went with it.
        val folder = java.nio.file.Files.createTempDirectory("codebridge-uri-folder")
        val uri = UriComponents.of(folder)
        assertTrue(!uri.path.endsWith("/"), uri.path)
        assertEquals(UriComponents.of(folder.resolve("a.rs")).path, uri.path + "/a.rs")
        // A root keeps its slash.
        assertTrue(UriComponents.of(folder.root).path.endsWith("/"))
    }

    private companion object {
        val WINDOWS = System.getProperty("os.name").startsWith("Windows")
    }
}
