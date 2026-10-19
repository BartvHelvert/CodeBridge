package me.helvert.codebridge.language

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep that bounds per-host scratch storage.
 *
 * It deletes directories, and the directories it deletes hold a private copy of every installed
 * extension and the host's own log, so the one thing that must never happen is it reaching a host
 * that is still running. Every project in the IDE shares the system directory, so "still running"
 * includes a sibling project's host, which is the case this covers.
 */
class HostStorageTest {

    @Test
    fun `abandoned storage is removed and a live host's is left alone`() {
        val root = Files.createTempDirectory("codebridge-host-storage")
        val live = storage(root, "host-live")
        val abandoned = storage(root, "host-abandoned")

        HostedLanguageServers.pruneAbandonedStorage(root, setOf(live))

        assertTrue(Files.isDirectory(live), "A running host's storage must survive the sweep")
        assertTrue(Files.exists(live.resolve("extensions/probe/package.json")), "and survive intact")
        assertFalse(Files.exists(abandoned), "Storage no host owns must go")
    }

    @Test
    fun `an empty root and an unreadable one are not worth failing a host start over`() {
        val root = Files.createTempDirectory("codebridge-host-storage-empty")
        HostedLanguageServers.pruneAbandonedStorage(root, emptySet())
        assertTrue(Files.isDirectory(root))

        // Never listed, never created: the sweep runs before the first host of a fresh install.
        HostedLanguageServers.pruneAbandonedStorage(root.resolve("absent"), emptySet())
    }

    @Test
    fun `a host gets the lowest slot no host holds, the same path every start`() {
        val root = Files.createTempDirectory("codebridge-host-storage-slots")
        val live = HashSet<Path>()
        val first = HostedLanguageServers.claimStorage(root, live)
        assertEquals(root.resolve("host-0"), first)
        // A sibling project's host, running beside it.
        assertEquals(root.resolve("host-1"), HostedLanguageServers.claimStorage(root, live))

        // The first stops; the next start sweeps its copy and runs from the same path again.
        live.remove(first)
        HostedLanguageServers.pruneAbandonedStorage(root, live)
        assertEquals(first, HostedLanguageServers.claimStorage(root, live))
    }

    @Test
    fun `a slot the sweep could not empty is passed over`() {
        val root = Files.createTempDirectory("codebridge-host-storage-held")
        storage(root, "host-0")
        assertEquals(root.resolve("host-1"), HostedLanguageServers.claimStorage(root, HashSet()))
    }

    private fun storage(root: Path, name: String): Path {
        val directory = root.resolve(name)
        Files.createDirectories(directory.resolve("extensions/probe"))
        directory.resolve("extensions/probe/package.json").writeText("{}")
        directory.resolve("exthost-stdout.log").writeText("started")
        return directory
    }
}
