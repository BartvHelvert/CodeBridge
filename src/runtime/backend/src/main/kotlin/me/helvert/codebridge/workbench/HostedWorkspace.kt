package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.workspace.WorkspaceFolder
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Duration

/**
 * `workspace.findFiles` and its kin, over the workspace's folders.
 *
 * [settings] are the settings in effect for a folder's resources, whose `files.exclude` a search
 * leaves out; null asks for a path in no folder.
 */
internal class HostedWorkspace(
    private val layoutOf: () -> CodeBridgeWorkspace,
    private val settings: (WorkspaceFolder?) -> JsonObject
) {
    constructor(layout: CodeBridgeWorkspace, settings: (WorkspaceFolder?) -> JsonObject) : this({ layout }, settings)

    private val layout: CodeBridgeWorkspace get() = layoutOf()

    fun handle(call: MainThreadCall, canceled: () -> Boolean = { false }): String? {
        val args = Args.of(call.method, call.argsJson)
        return when (call.method) {
            $$"$startFileSearch" -> {
                // No folder named is every folder, as in VS Code; the limit is for them all.
                val roots = args.uriOrNull(0)?.toPath()?.let(::listOf) ?: layout.folders.map { it.path }
                val options = args.obj(1)
                val maximum = options["maxResults"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
                val found = mutableListOf<Path>()
                for (root in roots) {
                    if (found.size >= maximum) break
                    found += search(root, options, canceled, maximum - found.size)
                }
                JsonArray(found.map { UriComponents.of(it).toJson() }).toString()
            }

            $$"$checkExists" -> {
                val roots = args.array(0).map { checkNotNull(UriComponents.from(it)).toPath() }
                exists(roots, args.stringsOrEmpty(1), canceled).toString()
            }

            else -> throw MainThreadException("Unavailable", "Unsupported workspace method ${call.method}")
        }
    }

    /** Whether a file under one of [roots] matches one of [patterns], whatever the exclude settings say. */
    fun exists(roots: List<Path>, patterns: List<String>, canceled: () -> Boolean = { false }): Boolean =
        roots.any { root ->
            patterns.any { pattern ->
                search(root, buildJsonObject {
                    put("includePattern", pattern); put("maxResults", 1); put("disregardExcludeSettings", true)
                }, canceled).isNotEmpty()
            }
        }

    private fun search(
        root: Path, options: JsonObject, canceled: () -> Boolean,
        maximum: Int = options["maxResults"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
    ): List<Path> {
        val ignoreCase = options["ignoreGlobCase"]?.jsonPrimitive?.booleanOrNull == true
        val include = (options["includePattern"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?.let { WorkspaceGlob(it, ignoreCase) }
        val excludes = mutableListOf<WorkspaceGlob>()
        fun add(pattern: String) {
            if (pattern.isNotEmpty()) excludes.add(WorkspaceGlob(pattern, ignoreCase))
        }
        when (val patterns = options["excludePattern"]) {
            is JsonPrimitive -> patterns.contentOrNull?.let(::add)
            is JsonArray -> patterns.forEach { element ->
                if (element is JsonPrimitive) element.contentOrNull?.let(::add)
                else (element as? JsonObject)?.get("pattern")?.jsonPrimitive?.contentOrNull?.let(::add)
            }

            else -> Unit
        }
        if (options["disregardExcludeSettings"]?.jsonPrimitive?.booleanOrNull != true) {
            val files = settings(layout.folderOf(root))["files.exclude"] as? JsonObject
            files?.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }?.keys?.forEach(::add)
        }
        if (maximum <= 0) return emptyList()
        val found = mutableListOf<Path>()
        fun excluded(path: Path): Boolean = excludes.any { it.matches(root.relativize(path).toString()) }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun checkCanceled() {
                if (canceled() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCanceled()
                return if (dir != root && excluded(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCanceled()
                if (!excluded(file) && (include == null || include.matches(
                        root.relativize(file).toString()
                    ))
                ) found.add(file)
                return if (found.size >= maximum) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                if (error is AccessDeniedException || error is NoSuchFileException) return FileVisitResult.CONTINUE
                throw error
            }
        })
        return found
    }

    companion object {
        private const val WORKSPACE_CONTAINS = "workspaceContains:"

        /**
         * Whether [layout] holds what one of [activationEvents]' `workspaceContains:` events names,
         * as the extension host decides for itself once it starts: a name is looked up in each
         * folder, and a glob -- one with `*` or `?` -- searched for. A search still running after
         * [timeout] counts as found, as VS Code counts it.
         */
        fun containsAny(layout: CodeBridgeWorkspace, activationEvents: Collection<String>, timeout: Duration): Boolean {
            val patterns = activationEvents.filter { it.startsWith(WORKSPACE_CONTAINS) }
                .map { it.removePrefix(WORKSPACE_CONTAINS) }
            val (globs, names) = patterns.partition { '*' in it || '?' in it }
            val roots = layout.folders.map { it.path }
            if (names.any { name ->
                    roots.any { root ->
                        runCatching { Files.exists(root.resolve(name)) }.getOrDefault(
                            false
                        )
                    }
                }) return true
            if (globs.isEmpty()) return false
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            return try {
                HostedWorkspace(layout) { JsonObject(emptyMap()) }.exists(roots, globs) { System.nanoTime() > deadline }
            } catch (canceled: java.util.concurrent.CancellationException) {
                // An interrupt -- the project closing -- is not a search that ran out of time.
                if (System.nanoTime() > deadline) true else throw canceled
            }
        }
    }
}
