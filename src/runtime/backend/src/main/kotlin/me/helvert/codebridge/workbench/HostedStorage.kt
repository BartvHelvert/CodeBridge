package me.helvert.codebridge.workbench

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Args
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Mementos survive host restarts; global state is shared across projects on this backend. */
internal class HostedStorage(
    private val root: Path,
    workspace: Path,
    private val notify: (String, String, String) -> Unit,
) : AutoCloseable {
    val globalHome: Path = root.resolve("globalStorage")
    val workspaceId: String = key(workspace.toAbsolutePath().normalize().toString())
    val workspaceHome: Path = root.resolve("workspaceStorage")
    private val watched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        instances.add(this)
    }

    fun handle(call: MainThreadCall): String? {
        val args = Args.of(call.method, call.argsJson)
        return when (call.method) {
            $$"$initializeExtensionStorage" -> {
                val global = args.bool(0)
                val id = args.str(1)
                if (global) watched.add(id)
                val file = stateFile(global, id)
                synchronized(lock(file)) {
                    // This result is a JSON string containing JSON, not the memento object itself.
                    if (Files.exists(file)) JsonPrimitive(Files.readString(file)).toString() else null
                }
            }

            $$"$setValue" -> {
                val global = args.bool(0)
                val id = args.str(1)
                val value = args.obj(2).toString()
                val file = stateFile(global, id)
                synchronized(lock(file)) {
                    writeAtomically(file, value)
                    if (global) instances.filter { it.root == root && id in it.watched }.forEach {
                        it.notify("ExtHostStorage", $$"$acceptValue", buildJsonArray {
                            add(true); add(id); add(value)
                        }.toString())
                    }
                }
                null
            }
            // Retain the declaration locally. Account-based Settings Sync is not provided.
            $$"$registerExtensionStorageKeysToSync" -> {
                val extension = args.obj(0)
                val file = root.resolve("syncKeys").resolve(key(extension.toString()) + ".json")
                synchronized(lock(file)) { writeAtomically(file, args.array(1).toString()) }
                null
            }

            else -> error("Unsupported storage method ${call.method}")
        }
    }

    private fun stateFile(global: Boolean, id: String): Path =
        (if (global) globalHome else workspaceHome.resolve(workspaceId)).resolve("mementos").resolve(key(id) + ".json")

    override fun close() {
        instances.remove(this); watched.clear()
    }

    companion object {
        private val instances = CopyOnWriteArrayList<HostedStorage>()
        private val locks = Array(64) { Any() }
        private fun lock(path: Path) = locks[(path.hashCode() and Int.MAX_VALUE) % locks.size]
        private fun key(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun writeAtomically(path: Path, text: String) {
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, ".state-", ".tmp")
            try {
                Files.writeString(temporary, text)
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
