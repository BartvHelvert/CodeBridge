package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.*
import me.helvert.codebridge.exthost.protocol.payload.Args
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** The host runs on this machine; file: operations must use this backend's filesystem. */
internal class HostedFileSystem(private val changed: (Path) -> Unit = {}) {
    fun handle(call: MainThreadCall): MainThreadResult = try {
        val args = Args.of(call.method, call.argsJson)
        fun path(index: Int): Path {
            val uri = args.uri(index)
            if (uri.scheme != "file") throw MainThreadException("Unavailable", "No filesystem provider for ${uri.scheme}")
            return uri.toPath().toAbsolutePath().normalize()
        }
        fun result(value: JsonElement) = MainThreadResult(value.toString())
        when (call.method) {
            $$"$ensureActivation" -> {
                if (args.str(0) != "file") throw MainThreadException("Unavailable", "No filesystem provider for ${args.str(0)}")
                MainThreadResult()
            }
            $$"$stat" -> {
                val file = path(0)
                val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
                result(buildJsonObject {
                    put("type", type(file)); put("ctime", attributes.creationTime().toMillis())
                    put("mtime", attributes.lastModifiedTime().toMillis()); put("size", attributes.size())
                    if (!Files.isWritable(file)) put("permissions", 1)
                })
            }
            $$"$readdir" -> result(Files.newDirectoryStream(path(0)).use { children ->
                buildJsonArray { children.sortedBy { it.fileName.toString() }.forEach { child ->
                    add(buildJsonArray { add(child.fileName.toString()); add(type(child)) })
                } }
            })
            $$"$readFile" -> {
                val file = path(0)
                if (Files.isDirectory(file)) throw MainThreadException("EntryIsADirectory", "$file is a directory")
                MainThreadResult(bytes = Files.readAllBytes(file))
            }
            $$"$writeFile" -> {
                val file = path(0)
                val bytes = call.buffers[1] ?: error("Missing binary content in ${call.method}")
                if (Files.isDirectory(file)) throw MainThreadException("EntryIsADirectory", "$file is a directory")
                Files.createDirectories(file.parent)
                Files.write(file, bytes); changed(file)
                MainThreadResult()
            }
            $$"$mkdir" -> { val file = path(0); Files.createDirectories(file); changed(file); MainThreadResult() }
            $$"$delete" -> {
                val file = path(0)
                val options = args.obj(1)
                if (options["useTrash"]?.jsonPrimitive?.booleanOrNull == true)
                    throw MainThreadException("Unavailable", "Trash deletion is not supported")
                remove(file, options["recursive"]?.jsonPrimitive?.booleanOrNull == true)
                changed(file); MainThreadResult()
            }
            $$"$rename", $$"$copy" -> {
                val source = path(0); val target = path(1)
                val overwrite = args.obj(2)["overwrite"]?.jsonPrimitive?.booleanOrNull == true
                if (source == target) return MainThreadResult()
                if (target.startsWith(source) || source.startsWith(target)) throw MainThreadException("Unavailable", "Source and destination overlap")
                Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) throw FileAlreadyExistsException(target.toString())
                val options = if (overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
                Files.createDirectories(target.parent)
                // Overwrite replaces the destination tree; merging would leave obsolete files.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && overwrite &&
                    (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))) remove(target, true)
                if (call.method == $$"$rename") Files.move(source, target, *options)
                else Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.createDirectories(target.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.copy(file, target.resolve(source.relativize(file)), LinkOption.NOFOLLOW_LINKS, *options)
                        return FileVisitResult.CONTINUE
                    }
                })
                changed(source); changed(target); MainThreadResult()
            }
            else -> throw MainThreadException("Unavailable", "Unsupported filesystem method ${call.method}")
        }
    } catch (error: FileSystemException) {
        val name = when (error) {
            is NoSuchFileException -> "EntryNotFound"
            is FileAlreadyExistsException -> "EntryExists"
            is AccessDeniedException -> "NoPermissions"
            is NotDirectoryException -> "EntryNotADirectory"
            else -> "Unavailable"
        }
        throw MainThreadException(name, error.message ?: name)
    }

    private fun remove(path: Path, recursive: Boolean) {
        if (!recursive || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { Files.delete(path); return }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }

    private fun type(file: Path): Int = (when {
        Files.isDirectory(file) -> 2
        Files.isRegularFile(file) -> 1
        else -> 0
    }) or (if (Files.isSymbolicLink(file)) 64 else 0)
}
