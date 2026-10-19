package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.workspace.WorkspaceFolder
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch disk changes, including changes made outside the IDE. One native watcher per host.
 *
 * Each workspace folder is watched whole, less its `files.watcherExclude` from [settings]; the
 * project directory itself only shallowly, where it is no folder, for its `.code-workspace` file.
 */
internal class HostedFileWatchers(
    initialLayout: CodeBridgeWorkspace,
    private val settings: (WorkspaceFolder) -> JsonObject,
    private val notify: (String, String, String) -> Unit,
    private val changed: (Path) -> Unit,
    private val report: (Throwable) -> Unit,
) : AutoCloseable {
    private data class Spec(
        val root: Path, val recursive: Boolean, val excludes: List<WorkspaceGlob>,
        val includes: List<WorkspaceGlob>, val session: Double?, val filter: Int?
    ) {
        fun contains(path: Path): Boolean = path == root || path.startsWith(root) && (recursive || path.parent == root)
        fun excluded(path: Path): Boolean {
            var candidate: Path? = path
            while (candidate != null && candidate.startsWith(root)) {
                if (excludes.any {
                        it.matches(
                            root.relativize(candidate).toString()
                        ) || it.matches(candidate.toString())
                    }) return true
                if (candidate == root) break
                candidate = candidate.parent
            }
            return false
        }

        fun accepts(path: Path, kind: WatchEvent.Kind<*>): Boolean {
            val bit = when (kind) {
                ENTRY_CREATE -> 4; ENTRY_DELETE -> 8; else -> 2
            }
            return contains(path) && !excluded(path) && (filter == null || filter and bit != 0) &&
                    (includes.isEmpty() || includes.any {
                        it.matches(root.relativize(path).toString()) || it.matches(
                            path.toString()
                        )
                    })
        }
    }

    @Volatile
    private var layout: CodeBridgeWorkspace = initialLayout
    private val service = FileSystems.getDefault().newWatchService()
    private val closed = AtomicBoolean()

    /** What extensions asked to watch, by session. */
    private val specs = linkedMapOf<Double, Spec>()

    /** Each workspace folder, watched for the host's own workspace-wide events. */
    private var folderSpecs: List<Spec> = emptyList()
    private val allSpecs: List<Spec> get() = folderSpecs + specs.values

    /** Directories always watched, whatever is excluded: each folder, its `.vscode`, and the project directory. */
    private val anchors: Set<Path>
        get() = layout.folders.flatMap { listOf(it.path, it.path.resolve(".vscode")) }.toSet().plusElement(layout.base)
    private val directories = linkedMapOf<Path, WatchKey>()
    private val paths = linkedMapOf<WatchKey, Path>()
    private val thread = Thread(::run, "codebridge-file-watcher").apply { isDaemon = true }

    init {
        folderSpecs = workspaceSpecs()
        registerFolders()
        thread.start()
    }

    private fun registerFolders() {
        layout.folders.forEach { registerTree(it.path, true) }
        if (Files.isDirectory(layout.base)) register(layout.base)
        layout.folders.map { it.path.resolve(".vscode") }.filter { Files.isDirectory(it) }.forEach(::register)
    }

    @Synchronized
    fun handle(call: MainThreadCall) {
        val args = Args.of(call.method, call.argsJson)
        when (call.method) {
            $$"$watch" -> {
                val options = args.obj(3)
                fun globs(key: String) =
                    (options[key] as? JsonArray).orEmpty().map { WorkspaceGlob(it.jsonPrimitive.content) }

                val path = args.uri(2).toPath().toAbsolutePath().normalize()
                val recursive = options["recursive"]?.jsonPrimitive?.booleanOrNull == true
                // `Math.random()`, not a counter: the host's own session ids are fractional, and
                // reading this as an integer failed every watch any extension ever registered.
                val session = args.double(1)
                specs[session] = Spec(
                    path, recursive, globs("excludes"), globs("includes"),
                    session.takeIf { args.boolOrNull(4) == true }, options["filter"]?.jsonPrimitive?.intOrNull
                )
                // Watching the parent also notices a watched file/root being replaced or created.
                var ancestor = path.parent
                while (ancestor != null && !Files.isDirectory(ancestor)) ancestor = ancestor.parent
                ancestor?.let(::register)
                registerTree(path, recursive)
            }

            $$"$unwatch" -> {
                specs.remove(args.double(0))
                dropUnused()
            }

            else -> error("Unsupported watcher method ${call.method}")
        }
    }

    /** Stops watching directories no folder or extension's watch needs any longer. */
    @Synchronized
    private fun dropUnused() {
        val anchors = anchors
        val unused = directories.keys.filter { dir ->
            dir !in anchors && allSpecs.none {
                it.contains(dir) || it.root.startsWith(dir)
            }
        }
        unused.forEach { dir -> directories.remove(dir)?.let { paths.remove(it); it.cancel() } }
    }

    /** The workspace's folders changed: [next]'s are watched, and those it no longer has are not. */
    @Synchronized
    fun relayout(next: CodeBridgeWorkspace) {
        layout = next
        folderSpecs = workspaceSpecs()
        dropUnused()
        registerFolders()
    }

    @Synchronized
    fun reloadExcludes() {
        folderSpecs = workspaceSpecs()
        registerFolders()
    }

    private fun workspaceSpecs(): List<Spec> = layout.folders.map { folder ->
        val excludes = (settings(folder)["files.watcherExclude"] as? JsonObject).orEmpty()
            .filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }.keys.map(::WorkspaceGlob)
        Spec(folder.path, true, excludes, emptyList(), null, null)
    }

    @Synchronized
    private fun register(directory: Path) {
        if (!closed.get() && directory !in directories) {
            val key = try {
                directory.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            } catch (failure: java.io.IOException) {
                // Gone before it could be watched, which scratch directories routinely are. On
                // Windows one that is being deleted refuses with "Access is denied" rather than
                // saying it is missing, and no longer reads as a directory either way.
                if (vanished(directory, failure)) return
                throw failure
            }
            directories[directory] = key; paths[key] = directory
        }
    }

    /**
     * Whether [failure] only says [path] is gone -- or going, which on Windows reads as "access
     * denied" until the delete completes. Either way there is nothing left to watch or report.
     */
    private fun vanished(path: Path, failure: java.io.IOException): Boolean =
        failure is NoSuchFileException || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                (failure is AccessDeniedException && !Files.isReadable(path))

    @Synchronized
    private fun registerTree(directory: Path, recursive: Boolean) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(directory, setOf(), if (recursive) Int.MAX_VALUE else 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (closed.get()) return FileVisitResult.SKIP_SUBTREE
                if (dir in anchors) {
                    register(dir); return FileVisitResult.CONTINUE
                }
                if (allSpecs.none { it.contains(dir) && !it.excluded(dir) }) return FileVisitResult.SKIP_SUBTREE
                register(dir); return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                if (!vanished(file, exc)) report(exc)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Everything below [directory], or as much of it as still exists.
     *
     * A directory can be gone again before its creation event is handled. Build tools do this
     * constantly -- cargo makes and deletes `target/debug/deps/rmeta*` scratch directories within
     * milliseconds -- so a vanished entry is skipped rather than failing the walk, which used to
     * report an exception and drop the event for the directory itself.
     */
    internal fun descendants(directory: Path): List<Path> {
        val found = mutableListOf<Path>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != directory) found.add(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                found.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                if (!vanished(file, exc)) report(exc)
                return FileVisitResult.CONTINUE
            }
        })
        return found
    }

    private fun run() {
        try {
            while (!closed.get()) {
                val key = service.take()
                val dir = synchronized(this) { paths[key] }
                if (dir != null) for (event in key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        report(java.io.IOException("Filesystem events overflowed for $dir")); continue
                    }
                    val path = dir.resolve(event.context() as Path).normalize()
                    try {
                        // Settings reload is independent of extension watcher exclusions.
                        changed(path)
                        if (event.kind() == ENTRY_CREATE && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            registerTree(path, true)
                            // A populated directory can be moved in before registration finishes.
                            descendants(path).forEach { dispatch(it, ENTRY_CREATE) }
                        }
                        dispatch(path, event.kind())
                    } catch (error: Exception) {
                        if (!closed.get()) report(error)
                    }
                }
                if (!key.reset()) synchronized(this) { paths.remove(key)?.let(directories::remove) }
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) report(error)
        }
    }

    private fun dispatch(path: Path, kind: WatchEvent.Kind<*>) {
        val sessions = synchronized(this) { allSpecs.filter { it.accepts(path, kind) }.map { it.session }.distinct() }
        for (session in sessions) {
            if (closed.get()) return
            notify("ExtHostFileSystemEventService", $$"$onFileEvent", buildJsonArray {
                add(buildJsonObject {
                    if (session != null) put("session", session)
                    for ((name, expected) in listOf(
                        "created" to ENTRY_CREATE,
                        "changed" to ENTRY_MODIFY,
                        "deleted" to ENTRY_DELETE
                    )) {
                        put(name, buildJsonArray { if (kind == expected) add(UriComponents.of(path).toJson()) })
                    }
                })
            }.toString())
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            service.close(); thread.interrupt()
        }
    }
}
