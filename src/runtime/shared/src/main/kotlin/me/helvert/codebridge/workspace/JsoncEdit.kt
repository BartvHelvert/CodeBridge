package me.helvert.codebridge.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Sets or removes one key in a JSON-with-comments document, leaving every other byte where it was.
 *
 * `.vscode/settings.json` is a file people edit by hand and commit: an extension changing one
 * setting must not reformat it, reorder it, or drop the comments explaining the others. So this
 * never re-serializes the document. It finds the span a key occupies and replaces only that --
 * the way VS Code's own settings writes behave.
 *
 * Structure is read from [Jsonc.stripToJson], which keeps every offset of the original, so a
 * position found in the stripped text is the same position in the file.
 */
object JsoncEdit {

    /**
     * [text] with [path] set to [value], or removed when [value] is null.
     *
     * [path] is the keys from the top-level object down: one for a setting, two for a key inside a
     * top-level object -- `["[rust]", "editor.tabSize"]`, which is how a language-scoped setting is
     * stored -- and one more in a `.code-workspace` file, whose settings are under `settings`.
     */
    fun set(text: String, path: List<String>, value: JsonElement?): String = set(text, path.map(Step::Key), value)

    /** A step of a path into a document: a key of an object, or an element of an array by index. */
    sealed interface Step {
        data class Key(val name: String) : Step
        data class Index(val index: Int) : Step
    }

    /**
     * [text] with the value at [path] set to [value], or removed when [value] is null. A missing key
     * is added, with the objects above it; an array element is only ever changed in place, so a
     * path through one that is not there fails.
     */
    @JvmName("setAt")
    fun set(text: String, path: List<Step>, value: JsonElement?): String {
        require(path.isNotEmpty()) { "A path names at least one key" }
        require(path.first() is Step.Key) { "A document holds an object" }
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        if (text.isBlank()) {
            if (value == null) return text
            require(path.all { it is Step.Key }) { "There is no array to change in an empty document" }
            return "{$newline  ${member(keys(path), value)}$newline}$newline"
        }
        val plain = Jsonc.stripToJson(text)
        val root = Scanner(plain).document() ?: error("The document does not hold a JSON object")
        return edit(text, plain, root, path, value, newline)
    }

    private fun edit(text: String, plain: String, target: Node, path: List<Step>, value: JsonElement?, newline: String): String =
        when (val step = path[0]) {
            is Step.Index -> {
                val array = target as? Arr ?: error("Expected an array at ${target.start}")
                val element = array.elements.getOrNull(step.index) ?: error("The array has no element ${step.index}")
                when {
                    path.size > 1 -> edit(text, plain, element, path.drop(1), value, newline)
                    value == null -> error("Array elements are changed, not removed")
                    else -> text.replaceRange(element.start, element.end, render(value))
                }
            }
            is Step.Key -> {
                val obj = target as? Obj ?: error("Expected an object at ${target.start}")
                edit(text, plain, obj, step.name, path.drop(1), value, newline)
            }
        }

    private fun edit(text: String, plain: String, target: Obj, key: String, rest: List<Step>, value: JsonElement?, newline: String): String {
        val existing = target.members.lastOrNull { it.key == key }
        val next = rest.firstOrNull()
        if (existing != null && (next is Step.Key && existing.value is Obj || next is Step.Index && existing.value is Arr)) {
            return edit(text, plain, existing.value, rest, value, newline)
        }
        val path = listOf(key) + keys(rest)
        return when {
            existing != null && value == null && rest.isEmpty() -> remove(text, plain, target, existing)
            existing != null && rest.isEmpty() -> text.replaceRange(existing.value.start, existing.value.end, render(value!!))
            // A key that is there with something other than an object where a section belongs:
            // replace it wholesale, as VS Code does.
            existing != null -> if (value == null) text else
                text.replaceRange(existing.value.start, existing.value.end, section(path.drop(1), value))
            value == null -> text
            else -> insert(text, target, path, value, newline)
        }
    }

    /** The keys of [path], which past a missing key are only keys: there is no array there to step into. */
    private fun keys(path: List<Step>): List<String> = path.map { step ->
        (step as? Step.Key)?.name ?: error("There is no array to change at element ${(step as Step.Index).index}")
    }

    private fun insert(text: String, target: Obj, path: List<String>, value: JsonElement, newline: String): String {
        val last = target.members.lastOrNull()
        if (last == null) {
            val outer = indentOf(text, target.start)
            val inner = "$outer  "
            return text.replaceRange(target.start + 1, target.end - 1,
                "$newline$inner${member(path, value)}$newline$outer")
        }
        // An object written on one line stays on one line.
        if ('\n' !in text.substring(target.start, target.end)) {
            return text.substring(0, last.value.end) + ", ${member(path, value)}" + text.substring(last.value.end)
        }
        val indent = indentOf(text, last.keyStart)
        return text.substring(0, last.value.end) + ",$newline$indent${member(path, value)}" +
            text.substring(last.value.end)
    }

    /**
     * Takes a member out together with the comma that joined it to its neighbours.
     *
     * The comma after it when there is one, else the one before it; and the whole line when the
     * member had it to itself, so no blank line is left behind.
     */
    private fun remove(text: String, plain: String, target: Obj, member: Member): String {
        var start = member.keyStart
        var end = member.value.end
        // The comma before it, taken out on its own: a comment after that comma stays.
        var before = -1
        val after = skipBlank(plain, end)
        if (after < plain.length && plain[after] == ',') {
            end = after + 1
        } else {
            val index = target.members.indexOf(member)
            if (index > 0) before = commaBefore(plain, target.members[index - 1].value.end, start)
        }
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it + 1 }
        if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
            start = lineStart
            end = lineEnd
        } else if (before >= 0 && plain.substring(before + 1, start).isBlank()) {
            // On one line with the member before it: the space after the comma goes too.
            start = before
            before = -1
        }
        val removed = text.removeRange(start, end)
        return if (before >= 0) removed.removeRange(before, before + 1) else removed
    }

    /** The comma between [from] and [to]; -1 when there is none. */
    private fun commaBefore(plain: String, from: Int, to: Int): Int {
        val comma = plain.indexOf(',', from)
        return if (comma in from until to) comma else -1
    }

    private fun member(path: List<String>, value: JsonElement): String =
        "${JsonPrimitive(path[0])}: ${if (path.size == 1) render(value) else section(path.drop(1), value)}"

    private fun section(path: List<String>, value: JsonElement): String = render(nested(path, value))

    /** [value] on one line, spaced as people write it: `["a", "b"]`, `{ "A": "1" }`. */
    fun render(value: JsonElement): String = when (value) {
        is JsonArray -> if (value.isEmpty()) "[]" else value.joinToString(", ", "[", "]") { render(it) }
        is JsonObject -> if (value.isEmpty()) "{}" else value.entries.joinToString(", ", "{ ", " }") { (key, item) -> "${JsonPrimitive(key)}: ${render(item)}" }
        else -> value.toString()
    }

    private fun nested(path: List<String>, value: JsonElement): JsonElement =
        if (path.isEmpty()) value else buildJsonObject { put(path[0], nested(path.drop(1), value)) }

    private fun indentOf(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun skipBlank(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private sealed class Node(val start: Int, val end: Int)
    private class Leaf(start: Int, end: Int) : Node(start, end)
    private class Obj(start: Int, end: Int, val members: List<Member>) : Node(start, end)
    private class Arr(start: Int, end: Int, val elements: List<Node>) : Node(start, end)
    private class Member(val key: String, val keyStart: Int, val value: Node)

    /** Positions only; the text between them is taken from the original, never rebuilt. */
    private class Scanner(private val text: String) {
        private var i = 0

        fun document(): Obj? {
            blank()
            return if (i < text.length && text[i] == '{') obj() else null
        }

        private fun value(): Node {
            blank()
            return when (text.getOrNull(i)) {
                '{' -> obj()
                '[' -> array()
                '"' -> {
                    val start = i
                    string()
                    Leaf(start, i)
                }
                else -> {
                    val start = i
                    while (i < text.length && text[i] !in ",}] \t\r\n") i++
                    Leaf(start, i)
                }
            }
        }

        private fun obj(): Obj {
            val start = i++
            val members = mutableListOf<Member>()
            while (true) {
                blank()
                when (text.getOrNull(i)) {
                    '}' -> return Obj(start, ++i, members)
                    ',' -> i++
                    '"' -> {
                        val keyStart = i
                        val key = string()
                        blank()
                        check(text.getOrNull(i) == ':') { "Expected ':' at $i" }
                        i++
                        members += Member(key, keyStart, value())
                    }
                    else -> error("Unexpected '${text.getOrNull(i)}' at $i")
                }
            }
        }

        private fun array(): Arr {
            val start = i++
            val elements = mutableListOf<Node>()
            while (true) {
                blank()
                when (text.getOrNull(i)) {
                    ']' -> return Arr(start, ++i, elements)
                    ',' -> i++
                    null -> error("Unterminated array")
                    else -> elements += value()
                }
            }
        }

        /** Reads a string and returns its contents, leaving [i] just past the closing quote. */
        private fun string(): String {
            val start = i
            i++
            while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
            i++
            return (Json.parseToJsonElement(text.substring(start, i)) as JsonPrimitive).content
        }

        private fun blank() {
            while (i < text.length && text[i].isWhitespace()) i++
        }
    }

    /** For tests and callers that want the result checked: the edited text, parsed. */
    fun parse(text: String): JsonObject = Json.parseToJsonElement(Jsonc.stripToJson(text)) as JsonObject
}
