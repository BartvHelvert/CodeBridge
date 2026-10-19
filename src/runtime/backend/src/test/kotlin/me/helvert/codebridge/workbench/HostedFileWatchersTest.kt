package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.concurrent.*
import kotlin.test.*

class HostedFileWatchersTest {
    @TempDir lateinit var temp: Path
    private val events = LinkedBlockingQueue<JsonObject>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val notify: (String, String, String) -> Unit = { service, _, args ->
        if (service == "ExtHostFileSystemEventService") events.add(Json.parseToJsonElement(args).jsonArray.single().jsonObject)
    }

    private fun await(kind: String, path: Path, session: Double? = null) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (event["session"]?.jsonPrimitive?.doubleOrNull != session) continue
            if (event[kind]!!.jsonArray.any { UriComponents.from(it)!!.toPath() == path }) return
        }
        fail("Missing $kind event for $path (session $session); watcher errors: $errors")
    }

    @Test fun `default workspace watcher delivers external create change and delete`() {
        val root = Files.createDirectories(temp.resolve("workspace"))
        HostedFileWatchers(CodeBridgeWorkspace.single(root), { buildJsonObject {} }, notify, {}, errors::add).use {
            val file = root.resolve("created.txt")
            Files.writeString(file, "first"); await("created", file)
            Files.writeString(file, "second"); await("changed", file)
            Files.delete(file); await("deleted", file)
            val directory = Files.createDirectory(root.resolve("new-directory"))
            await("created", directory)
            val nested = Files.writeString(directory.resolve("nested.txt"), "nested")
            await("created", nested)
        }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `directories that vanish before they are walked are not errors`() {
        val root = Files.createDirectories(temp.resolve("workspace"))
        HostedFileWatchers(CodeBridgeWorkspace.single(root), { buildJsonObject {} }, notify, {}, errors::add).use { watchers ->
            assertEquals(emptyList(), watchers.descendants(root.resolve("never-existed")))
            // What cargo does under target/debug/deps: scratch directories made and removed at once.
            val deps = Files.createDirectories(root.resolve("target/debug/deps"))
            repeat(100) {
                val scratch = Files.createDirectory(deps.resolve("rmeta$it"))
                Files.writeString(scratch.resolve("lib.rmeta"), "x")
                Files.delete(scratch.resolve("lib.rmeta"))
                Files.delete(scratch)
            }
            val stable = Files.writeString(root.resolve("after.txt"), "still watching")
            await("created", stable)
        }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `explicit external watcher honors event filters and unwatch`() {
        val root = Files.createDirectories(temp.resolve("workspace"))
        val external = Files.createDirectories(temp.resolve("external"))
        HostedFileWatchers(CodeBridgeWorkspace.single(root), { buildJsonObject {} }, notify, {}, errors::add).use { watchers ->
            val args = buildJsonArray {
                // The shape the host really sends: `Math.random()`, not a counter. An integer here
                // is what let a session id that never parses reach production.
                add("p.ext"); add(SESSION); add(UriComponents.of(external).toJson())
                add(buildJsonObject { put("recursive", true); put("filter", 4); put("excludes", buildJsonArray { add("**/*.ignored") }) })
                add(true)
            }
            watchers.handle(MainThreadCall("MainThreadFileSystemEventService", $$"$watch", args.toString()))
            val file = Files.writeString(external.resolve("file.txt"), "first")
            await("created", file, SESSION)
            Files.writeString(file, "second")
            Files.writeString(external.resolve("skip.ignored"), "skip")
            assertNull(events.poll(250, TimeUnit.MILLISECONDS), "Filtered events should not be sent")
            watchers.handle(MainThreadCall("MainThreadFileSystemEventService", $$"$unwatch", "[$SESSION]"))
            Files.writeString(external.resolve("after-unwatch.txt"), "after")
            assertNull(events.poll(250, TimeUnit.MILLISECONDS), "Unwatched events should not be sent")
        }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `saved settings reach the host through the workbench watcher`() {
        val root = Files.createDirectories(temp.resolve("workspace"))
        val settingsDir = Files.createDirectories(root.resolve(".vscode"))
        Files.writeString(settingsDir.resolve("settings.json"), "{\"server.enabled\":true}")
        val changes = LinkedBlockingQueue<JsonArray>()
        HostedWorkbench(root, temp.resolve("state"), emptyList(), { service, method, args ->
            if (service == "ExtHostConfiguration" && method == $$"$acceptConfigurationChanged") changes.add(Json.parseToJsonElement(args).jsonArray)
            ""
        }, { message, error -> if (error != null) errors.add(error) else fail(message) }).use { workbench ->
            workbench.startWatching(); workbench.initialized()
            // Atomic replacement is how many editors save settings.
            val replacement = Files.writeString(settingsDir.resolve("replacement.tmp"), "{\"server.enabled\":false}")
            Files.move(replacement, settingsDir.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING)
            val change = assertNotNull(changes.poll(5, TimeUnit.SECONDS))
            assertFalse(change[0].jsonObject["workspace"]!!.jsonObject["contents"]!!.jsonObject["server"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("server.enabled"), change[1].jsonObject["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    private companion object {
        /** A real one: the host generates watch sessions with `Math.random()`. */
        const val SESSION = 0.5143866463427027
    }
}
