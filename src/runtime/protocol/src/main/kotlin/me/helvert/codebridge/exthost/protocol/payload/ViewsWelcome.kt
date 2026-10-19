package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import java.net.URLDecoder

/**
 * `contributes.viewsWelcome`: what a view shows while it has nothing in it -- a sentence or two and
 * a button such as "Open Folder" -- each entry under its own `when` clause.
 *
 * VS Code reads the contents a line at a time. A line that is a single link is a button; any other
 * line is text whose links can be clicked. A link runs a command (`command:id?args`, the arguments
 * URI-encoded JSON) or opens a web address.
 */
object ViewsWelcome {

    /** Text, or a link to [target] reading [text]. */
    data class Part(val text: String, val target: String? = null)

    data class Line(val parts: List<Part>, val button: Boolean)

    data class Entry(val whenClause: String?, val lines: List<Line>)

    private val LINK = Regex("""\[([^]]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

    /** The welcome entries [manifest] contributes to view [viewId], in order. */
    fun of(manifest: JsonObject, viewId: String): List<Entry> {
        val entries = (manifest["contributes"] as? JsonObject)?.get("viewsWelcome") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            if (entry.string("view") != viewId) return@mapNotNull null
            val contents = entry.string("contents") ?: return@mapNotNull null
            Entry(entry.string("when"), lines(contents))
        }
    }

    fun lines(contents: String): List<Line> =
        contents.lines().map(String::trim).filter(String::isNotEmpty).map { text ->
            val only = LINK.matchEntire(text)
            if (only != null) Line(listOf(Part(only.groupValues[1], only.groupValues[2])), button = true)
            else Line(parts(text), button = false)
        }

    private fun parts(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        var at = 0
        for (match in LINK.findAll(text)) {
            if (match.range.first > at) parts += Part(text.substring(at, match.range.first))
            parts += Part(match.groupValues[1], match.groupValues[2])
            at = match.range.last + 1
        }
        if (at < text.length) parts += Part(text.substring(at))
        return parts
    }

    /**
     * The command a `command:` link runs and its arguments, or null for any other link. Arguments
     * that do not decode are dropped, as VS Code drops them.
     */
    fun command(target: String): Pair<String, JsonArray>? {
        if (!target.startsWith("command:")) return null
        val rest = target.removePrefix("command:")
        val id = rest.substringBefore('?').takeIf { it.isNotEmpty() } ?: return null
        val query = rest.substringAfter('?', "")
        if (query.isEmpty()) return id to JsonArray(emptyList())
        val decoded = runCatching { Json.parseToJsonElement(URLDecoder.decode(query, Charsets.UTF_8)) }.getOrNull()
            ?: return id to JsonArray(emptyList())
        return id to (decoded as? JsonArray ?: JsonArray(listOf(decoded)))
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
