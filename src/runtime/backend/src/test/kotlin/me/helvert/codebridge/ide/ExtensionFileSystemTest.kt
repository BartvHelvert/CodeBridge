package me.helvert.codebridge.ide

import com.intellij.testFramework.PlatformTestUtil
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.started
import me.helvert.codebridge.workbench.HostedContentProviders
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file system an extension provides, the way GitLens serves revisions under `gitlens:`: what the
 * diff viewer reads both sides of a comparison from.
 */
class ExtensionFileSystemTest : EditorFixture() {

    @Test
    fun `a registered file system is read through the host, as UTF-8 with its line breaks normalized`() = onEdt {
        val read = mutableListOf<Pair<Int, String>>()
        contents().readFileForTest { handle, resource ->
            read += handle to resource.uri
            "\uFEFFclass Main {}\r\n".toByteArray()
        }
        assertTrue(contents().handleFileSystem($$"$registerFileSystemProvider", """[7,"gitlens",2]"""))
        assertTrue(contents().providesFileSystem("gitlens"))
        assertTrue(contents().provides("gitlens"))

        val revision = UriComponents("gitlens", "", "/c:/p/src/Main.java", "{\"ref\":\"33ce361\"}", "")
        assertEquals("class Main {}\n", PlatformTestUtil.waitForFuture(started { contents().content(revision) }, 5_000))
        assertEquals(7, read.single().first)

        assertTrue(contents().handleFileSystem($$"$unregisterProvider", "[7]"))
        assertFalse(contents().providesFileSystem("gitlens"))
        assertNull(PlatformTestUtil.waitForFuture(started { contents().content(revision) }, 5_000))
    }

    @Test
    fun `other file system calls are left to the workbench`() = onEdt {
        assertFalse(contents().handleFileSystem($$"$stat", """[{"scheme":"file","path":"/p"}]"""))
    }

    @AfterEach
    fun forget() = onEdt { contents().hostStopped() }

    private fun contents() = HostedContentProviders.getInstance(fixture.project)
}
