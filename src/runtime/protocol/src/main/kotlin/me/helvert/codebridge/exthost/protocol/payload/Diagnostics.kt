package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * One problem, as the workbench receives it on `MainThreadDiagnostics`.
 *
 * VS Code has already converted whatever the extension produced -- LSP diagnostics from a language
 * server, or ones built by hand in JavaScript -- into editor markers in the 1-based coordinates of
 * its own editor model. That conversion is why this bridge does not need to speak LSP to show a
 * language server's diagnostics.
 */
data class Marker(
    val range: Range,
    val severity: Int,
    val message: String,
    val source: String?,
    val tags: List<Int> = emptyList(),
    val code: Code? = null,
) {

    /**
     * What the problem is called in its source's own vocabulary -- a lint name, an error number --
     * and, optionally, where to read more about it. VS Code sends a bare string or `{value, target}`.
     */
    data class Code(val value: String, val target: UriComponents? = null) {

        companion object {
            fun from(element: JsonElement?): Code? = when (element) {
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }?.let { Code(it) }
                is JsonObject -> element["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?.let { Code(it, UriComponents.from(element["target"])) }

                else -> null
            }
        }
    }

    /** `IMarkerData`, as [from] reads it: the range flattened into the marker, the code a bare string. */
    fun toJson(): JsonObject = buildJsonObject {
        put("startLineNumber", range.startLineNumber)
        put("startColumn", range.startColumn)
        put("endLineNumber", range.endLineNumber)
        put("endColumn", range.endColumn)
        put("severity", severity)
        put("message", message)
        source?.let { put("source", it) }
        code?.let { put("code", it.value) }
        if (tags.isNotEmpty()) putJsonArray("tags") { tags.forEach { add(it) } }
    }

    companion object {

        /** `MarkerSeverity.Error`; the scale is 1 hint, 2 info, 4 warning, 8 error. */
        const val ERROR: Int = 8

        /** `MarkerSeverity.Warning`. */
        const val WARNING: Int = 4

        /** `MarkerSeverity.Info`. */
        const val INFO: Int = 2

        /** `MarkerTag.Unnecessary`, used for unused or unreachable code. */
        const val UNNECESSARY: Int = 1

        /** `MarkerTag.Deprecated`, drawn struck through. */
        const val DEPRECATED: Int = 2

        fun from(element: JsonElement): Marker {
            val json = element as? JsonObject
                ?: throw PayloadException("A diagnostic marker is not an object but $element")

            fun int(name: String, fallback: Int) = json[name]?.jsonPrimitive?.intOrNull ?: fallback
            return Marker(
                range = Range(
                    startLineNumber = int("startLineNumber", 1),
                    startColumn = int("startColumn", 1),
                    endLineNumber = int("endLineNumber", 1),
                    endColumn = int("endColumn", 1),
                ),
                severity = int("severity", ERROR),
                message = json["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                source = json["source"]?.jsonPrimitive?.contentOrNull,
                tags = (json["tags"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty(),
                code = Code.from(json["code"]),
            )
        }
    }
}

/** What one `$changeMany` entry says about one resource: its problems, or null to clear them. */
data class DiagnosticChange(val uri: UriComponents, val markers: List<Marker>?)

/**
 * `MainThreadDiagnostics.$changeMany(owner, entries)`.
 *
 * Each entry pairs a resource with its markers, or with null to withdraw every problem previously
 * reported for it. A push replaces what was there before rather than adding to it, which is why
 * clearing has to be distinguishable from "reported nothing this time".
 */
object Diagnostics {

    const val CHANGE_MANY: String = $$"$changeMany"

    const val CLEAR: String = $$"$clear"

    /**
     * Argument 0: which collection is speaking.
     *
     * One extension routinely owns several. A language client that both receives pushed diagnostics
     * and pulls them keeps two, and they report about the same file independently -- so a push is a
     * replacement *within an owner*, never across them. Filing them all under the resource alone
     * lets whichever spoke last erase the other's findings, which looks like problems that appear
     * and then vanish for no reason.
     */
    fun owner(argsJson: String): String = Args.of(CHANGE_MANY, argsJson).strOrNull(0).orEmpty()

    /** `$clear(owner)` withdraws everything one collection reported, and nothing else. */
    fun clearedOwner(argsJson: String): String = Args.ofOrNull(CLEAR, argsJson)?.strOrNull(0).orEmpty()

    /** The host side, which holds every collection `languages.getDiagnostics` reads. */
    const val EXT_HOST: String = "ExtHostDiagnostics"

    const val ACCEPT_MARKERS_CHANGE: String = $$"$acceptMarkersChange"

    /**
     * `ExtHostDiagnostics.$acceptMarkersChange(entries)`: problems the workbench found itself, which
     * the host files in a mirror collection of their own, alongside every extension's.
     *
     * Each entry replaces what was said about its resource before; an empty list withdraws it. The
     * mirror has no way back to the workbench, so nothing sent here returns on [CHANGE_MANY].
     */
    fun acceptMarkersChange(entries: List<Pair<UriComponents, List<Marker>>>): String = buildJsonArray {
        addJsonArray {
            for ((uri, markers) in entries) addJsonArray {
                add(uri.toJson())
                addJsonArray { markers.forEach { add(it.toJson()) } }
            }
        }
    }.toString()

    fun changes(argsJson: String): List<DiagnosticChange> {
        val entries = Args.of(CHANGE_MANY, argsJson).arrayOrNull(1) ?: return emptyList()
        return entries.mapNotNull { entry ->
            val pair = entry.jsonArray
            val uri = UriComponents.from(pair.getOrNull(0)) ?: return@mapNotNull null
            val markers = pair.getOrElse(1) { JsonNull }
            DiagnosticChange(uri, if (markers is JsonNull) null else markers.jsonArray.map(Marker::from))
        }
    }
}
