package me.helvert.codebridge.exthost.protocol

import java.nio.file.Files
import java.nio.file.Path

/**
 * Maps the numeric `rpcId` carried in every RPC message to the service it addresses.
 *
 * VS Code assigns these by the order in which `createProxyIdentifier` is called, in a single
 * counter shared by both directions, so the table is simply an ordered list of names starting at
 * 1. `MainThread*` entries come first, then `ExtHost*`.
 *
 * The table is therefore specific to a build, but it can be regenerated mechanically rather than
 * by reading source, which is what makes pinning a VS Code version affordable:
 *
 * ```
 * grep -o 'V("[A-Za-z0-9]*")' extensionHostProcess.js | sed 's/V("//;s/")//'
 * ```
 */
class RpcIdTable(private val names: List<String>) {

    val size: Int get() = names.size

    private val idsByName: Map<String, Int> =
        names.withIndex().associate { (index, name) -> name to index + 1 }

    fun name(rpcId: Int): String? = names.getOrNull(rpcId - 1)

    fun id(name: String): Int? = idsByName[name]

    /** The id of [name], or an error naming the build, since a missing entry means a version skew. */
    fun require(name: String): Int =
        idsByName[name] ?: throw IllegalArgumentException(
            "no rpcId for \"$name\" in a table of $size entries; the table may be from a different VS Code build"
        )

    fun mainThreadServices(): List<String> = names.filter { it.startsWith("MainThread") }

    fun extHostServices(): List<String> = names.filter { it.startsWith("ExtHost") }

    companion object {

        /** The build the bundled fallback table was extracted from. */
        const val BUNDLED_BUILD: String = "1.132.1"

        fun parse(text: String): RpcIdTable =
            RpcIdTable(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())

        /**
         * Extracts the table from a build's `extensionHostProcess.js`.
         *
         * Always prefer this to [bundled]. The ids shift when services are added: they are
         * identical between 1.132.1 and 1.133.0 but diverge from id 33 onward against 1.127.0, and
         * a stale table does not fail loudly - it silently routes calls to the wrong service.
         * Reading them from the very build we are about to launch makes skew impossible.
         *
         * `createProxyIdentifier` is minified to a different name in each build (`V` in 1.132.1,
         * `B` in 1.127.0), so it is identified by behaviour: the function most often called with a
         * single string literal naming a `MainThread*` or `ExtHost*` service.
         */
        fun extractFrom(extensionHostProcessJs: Path): RpcIdTable =
            extract(Files.readString(extensionHostProcessJs))

        internal fun extract(source: String): RpcIdTable {
            val named = Regex("""([A-Za-z_$][A-Za-z0-9_$]*)\("((?:MainThread|ExtHost)[A-Za-z0-9]*)"\)""")
            val factory = named.findAll(source)
                .groupingBy { it.groupValues[1] }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: throw IllegalArgumentException("no proxy identifier calls found; not an extensionHostProcess.js?")

            // Some entries do not name a service, so this pass must not filter on the name.
            val calls = Regex("""\b${Regex.escape(factory)}\("([A-Za-z0-9]+)"\)""")
            val names = calls.findAll(source).map { it.groupValues[1] }.toList()
            require(names.isNotEmpty()) { "proxy identifier factory \"$factory\" yielded no entries" }
            return RpcIdTable(names)
        }

        /** Loads the table bundled for [build]. */
        fun bundled(build: String = BUNDLED_BUILD): RpcIdTable {
            val resource = "/codebridge/rpc-ids-$build.txt"
            val text = RpcIdTable::class.java.getResourceAsStream(resource)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("no bundled rpcId table for VS Code $build (looked for $resource)")
            return parse(text)
        }
    }
}
