package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Path

/**
 * How every resource is spelled on this protocol: VS Code's `UriComponents`, never a URI string.
 *
 * Each side takes the shape apart the moment it arrives, and the way it does so is the whole
 * difficulty. `path` is VS Code's `/C:/x` spelling, which `java.net.URI` reads correctly only once
 * a scheme is put back in front of it -- and IntelliJ's own `VirtualFile.url` spells the same file
 * `file://C:/x`, where the drive letter parses as the *authority* and the path comes out as `/x`.
 * A resource that makes that round trip badly is not rejected by anything: the host simply files
 * the document under a name the workbench never asks about again, and an extension answers hovers
 * and reports problems against a file nobody is looking at.
 *
 * Four call sites used to rebuild `"file://" + path` by hand, two of them with a comment about this
 * trap. They now all come through here, and [key] is the one normalization the host's document map
 * is keyed by.
 */
data class UriComponents(
    val scheme: String,
    val authority: String,
    val path: String,
    val query: String,
    val fragment: String,
) {

    /** The standards-shaped URI, which is what `java.net.URI` and the host both read correctly. */
    val uri: String
        get() =
            // An untitled buffer's path is a bare name, which `java.net.URI` only accepts as an opaque URI.
            if (authority.isEmpty() && !path.startsWith("/")) URI(
                scheme,
                path,
                fragment.ifEmpty { null }).toASCIIString()
            else URI(scheme, authority, path, query.ifEmpty { null }, fragment.ifEmpty { null }).toASCIIString()

    fun toJson(): JsonObject = buildJsonObject {
        put("scheme", scheme)
        put("authority", authority)
        put("path", path)
        put("query", query)
        put("fragment", fragment)
    }

    /** The local file this names, for a `file:` resource. */
    fun toPath(): Path {
        require(scheme == FILE) { "Not a local file resource: $scheme" }
        // Through the URI's ASCII spelling: the constructor leaves a character like `é` as it is, which
        // Windows accepts and Linux refuses as a bad escape.
        return Path.of(URI(URI(scheme, authority, path, null, null).toASCIIString()))
    }

    companion object {

        const val FILE: String = "file"

        /** Buffers an extension created that have never been saved: `untitled:Untitled-1`. */
        const val UNTITLED: String = "untitled"

        /** A local path, as the host expects to receive it: absolute, forward slashes, leading `/`. */
        /**
         * A file's URI as VS Code's `URI.file` makes it. `Path.toUri` ends an existing directory in
         * `/`, which VS Code never does: extensions put `${workspaceFolder}` in for the folder's path,
         * and with the slash taken along, rust-analyzer's `${workspaceFolder}	arget` ran together
         * into `rust-sandboxtarget`. A root keeps its slash, as it does in VS Code.
         */
        fun of(path: Path): UriComponents {
            val uri = of(path.toAbsolutePath().toUri().toString())
            val root = uri.path == "/" || ROOT.matches(uri.path)
            return if (uri.path.endsWith("/") && !root) uri.copy(path = uri.path.trimEnd('/')) else uri
        }

        private val ROOT = Regex("/[A-Za-z]:/")

        /**
         * Takes a URI string apart: a file, an untitled buffer, or a document an extension provides
         * under a scheme of its own -- Java's `jdt:` for a class in a library jar. The host is sent
         * the text of every document mirrored, so it never reads one from where the URI points.
         */
        fun of(value: String): UriComponents {
            val uri = URI(value)
            requireNotNull(uri.scheme) { "Not a URI: $value" }
            // `untitled:Untitled-1` and the like: no authority, and the path is everything after the scheme.
            if (uri.isOpaque) return UriComponents(
                uri.scheme,
                "",
                uri.schemeSpecificPart,
                "",
                uri.fragment ?: ""
            )
            requireNotNull(uri.path) { "Not a resource: $value" }
            return UriComponents(
                scheme = uri.scheme,
                authority = uri.authority ?: "",
                path = uri.path,
                query = uri.query ?: "",
                fragment = uri.fragment ?: "",
            )
        }

        /** Reads the shape off the wire, or `null` when [element] is not one. */
        fun from(element: JsonElement?): UriComponents? {
            val json = element as? JsonObject ?: return null
            val path = json["path"]?.jsonPrimitive?.contentOrNull ?: return null
            return UriComponents(
                scheme = json["scheme"]?.jsonPrimitive?.contentOrNull ?: FILE,
                authority = json["authority"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                path = path,
                query = json["query"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                fragment = json["fragment"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

        /**
         * The spelling two sides can compare, including VS Code's lowercase Windows drive.
         *
         * The host matches documents by string. `file:///C:/x` and `file:///c:/x` are the same file
         * to a filesystem and two different documents to it, so everything keyed by a URI is keyed
         * by this.
         */
        fun key(value: String): String {
            val uri = URI(value)
            if (uri.isOpaque) return uri.toASCIIString()
            var path = uri.path ?: error("URI has no path: $value")
            if (uri.scheme == FILE && path.length >= 3 && path[0] == '/' && path[2] == ':') {
                path = "/" + path[1].lowercaseChar() + path.substring(2)
            }
            return URI(uri.scheme, uri.authority, path, uri.query, uri.fragment).toASCIIString()
        }

        /** [key] for a path the workbench holds, without spelling the URI out first. */
        fun key(path: Path): String = key(of(path).uri)
    }
}
