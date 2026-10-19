package me.helvert.codebridge.language

/**
 * Translates a VS Code snippet into what IntelliJ can insert: its plain text, and a live template.
 *
 * Extensions send completions as snippets constantly -- a function signature with its parameters as
 * tab stops, a tag with its closing half. IntelliJ has its own template engine and none of this
 * syntax, so inserting the snippet verbatim would put `${1:name}` into the user's file. The plain
 * text is what the lookup shows; the template is what gets inserted, so Tab walks the stops in the
 * order VS Code would: `$1`, `$2`, ..., and `$0` last.
 *
 * Grammar, as much of it as matters here:
 *
 * | Form | Text | Template |
 * |---|---|---|
 * | `$1`, `${1}` | the stop's value, if it is given one elsewhere | an empty stop, or a mirror of it |
 * | `${1:default}` | `default`, itself translated recursively | a stop starting as `default` |
 * | `${1\|one,two\|}` | `one`, the choice VS Code preselects | a stop offering both |
 * | `$0` | nothing | where the caret ends up; a second `$0`, where another caret does |
 * | `${1/regex/replace/flags}` | nothing; a transform has no variable to transform | nothing |
 * | `$name`, `${name:default}` | nothing, or `default` | the same, as literal text |
 * | `\$`, `\}`, `\\` | the character itself | the same |
 *
 * IntelliJ's variables cannot nest, so a stop inside a placeholder -- `${1:a.${2:b}}` -- becomes part
 * of the outer stop's default text rather than a stop of its own.
 *
 * A stop written more than once shows the same value everywhere, as VS Code's parser fills it in:
 * `${1:x} = $1` is `x = x`.
 */
object Snippets {

    /**
     * [template] is IntelliJ template text: `$TAB_1$` for each stop, `$END$` for `$0`, and every
     * literal `$` doubled. [stops] are its variables, in the order Tab visits them. [end] is where
     * the first `$0` falls in [text], if the snippet has one. [marks] are where in [text] each stop is
     * written, in text order, for placing the stops over text already inserted.
     *
     * Every `$0` after the first is one of [finals]: a template variable never stopped at, holding the
     * `$0`'s value, where a caret is added once the template finishes. VS Code puts a cursor on every
     * `$0` -- rust-analyzer's `pd` is `eprintln!("$0 = {:?}", $0);`, typed into twice at once.
     */
    data class Expansion(
        val text: String,
        val template: String,
        val stops: List<TabStop>,
        val end: Int? = null,
        val marks: List<Mark> = emptyList(),
        val finals: List<TabStop> = emptyList(),
    ) {
        /** Where in [text] every `$0` is, in text order. */
        val ends: List<Mark> get() = marks.filter { it.number == 0 }
    }

    /** One template variable: its [value] when the template starts, and any [choices] to pick from. */
    data class TabStop(val name: String, val value: String, val choices: List<String>)

    /**
     * Stop [number] written at `start until end` of the text, its value there; 0 is the final stop.
     * A stop nested in another's value has no mark of its own.
     */
    data class Mark(val number: Int, val start: Int, val end: Int)

    /** How the editor indents: [size] columns at a time, in spaces or else in tabs. */
    data class Indentation(val size: Int, val spaces: Boolean)

    const val END: String = $$"$END$"

    fun plain(snippet: String): String = expand(snippet).text

    fun expand(snippet: String): Expansion {
        // Twice: a mirror written before the placeholder that gives it a value shows that value too.
        val values = Parser(emptyMap()).apply { append(snippet, nested = false) }.values
        val parser = Parser(values)
        parser.append(snippet, nested = false)
        // VS Code visits $1, $2, ... in order and $0, the final position, last.
        val stops = parser.values.keys.sorted().map { number ->
            TabStop(name(number), parser.values.getValue(number), parser.choices[number].orEmpty())
        }
        return Expansion(
            parser.text.toString(), parser.template.toString(), stops, parser.end, parser.marks, parser.finals,
        )
    }

    /** The template variable stop [number] is. */
    fun name(number: Int): String = "TAB_$number"

    /** The template variable the [index]th `$0` after the first is; see [Expansion.finals]. */
    fun finalName(index: Int): String = "FINAL_$index"

    /**
     * [snippet] indented to go into a line whose leading whitespace is [line], as VS Code adjusts a
     * snippet's whitespace unless told to keep it: every line after the first starts with [line],
     * and leading whitespace is redone in the editor's tabs or spaces. Line breaks become `\n`.
     */
    fun indented(snippet: String, line: String, indentation: Indentation): String =
        snippet.split("\r\n", "\n", "\r").withIndex().joinToString("\n") { (index, text) ->
            val leading = text.takeWhile { it == ' ' || it == '\t' }
            normalized(if (index == 0) leading else line + leading, indentation) + text.substring(leading.length)
        }

    /**
     * [whitespace] as the editor would write it: VS Code's `normalizeIndentation`, which counts a tab
     * as a full indent wherever it falls.
     */
    private fun normalized(whitespace: String, indentation: Indentation): String {
        val size = indentation.size.coerceAtLeast(1)
        val columns = whitespace.sumOf { if (it == '\t') size else 1 }
        return if (indentation.spaces) " ".repeat(columns) else "\t".repeat(columns / size) + " ".repeat(columns % size)
    }

    /** Parses a snippet; [known] are the stops' values, where a first pass has found them. */
    private class Parser(private val known: Map<Int, String>) {
        val text = StringBuilder()
        val template = StringBuilder()
        val values = HashMap<Int, String>()
        val choices = HashMap<Int, List<String>>()
        val marks = mutableListOf<Mark>()
        val finals = mutableListOf<TabStop>()
        var end: Int? = null
            private set

        /**
         * Appends [snippet] to the text and, unless it is [nested] inside a stop whose value it is,
         * to the template.
         */
        fun append(snippet: String, nested: Boolean) {
            var index = 0
            while (index < snippet.length) {
                when (val character = snippet[index]) {
                    '\\' if index + 1 < snippet.length -> {
                        literal(snippet[index + 1], nested); index += 2
                    }
                    '$' if index + 1 < snippet.length && snippet[index + 1] == '{' -> {
                        val end = closing(snippet, index + 1)
                        if (end < 0) { literal(character, nested); index++ }
                        else { body(snippet.substring(index + 2, end), nested); index = end + 1 }
                    }
                    '$' if index + 1 < snippet.length && isName(snippet[index + 1]) -> {
                        val start = ++index
                        // A tab stop is only digits, so `$1abc` is stop 1 followed by `abc`.
                        val part: (Char) -> Boolean = if (snippet[start].isDigit()) Char::isDigit else ::isName
                        while (index < snippet.length && part(snippet[index])) index++
                        snippet.substring(start, index).toIntOrNull()?.let { stop(it, "", nested) }
                    }
                    else -> { literal(character, nested); index++ }
                }
            }
        }

        /** The inside of `${...}`: a stop, a choice, a transform, or a variable with a default. */
        private fun body(inner: String, nested: Boolean) {
            val separator = inner.indexOfFirst { it == ':' || it == '|' || it == '/' }
            val number = (if (separator < 0) inner else inner.substring(0, separator)).toIntOrNull()
            when (inner.getOrNull(separator)) {
                ':' -> {
                    val start = text.length
                    // A variable is not a stop, so its default belongs to the template as it stands.
                    append(inner.substring(separator + 1), nested = nested || number != null)
                    if (number != null) stop(number, text.substring(start), nested, appendText = false)
                }
                '|' -> if (number != null) {
                    val options = options(inner.substring(separator + 1).removeSuffix("|"))
                    if (!nested && options.size > 1) choices.putIfAbsent(number, options)
                    stop(number, options.firstOrNull().orEmpty(), nested)
                }
                // A transform produces text from a variable we do not have, so it produces none.
                '/' -> {}
                else -> number?.let { stop(it, "", nested) }
            }
        }

        /**
         * Records stop [number] holding [value]; the text gets [value] unless the caller already
         * appended it. Nested, a stop is nothing but its text.
         */
        private fun stop(number: Int, value: String, nested: Boolean, appendText: Boolean = true) {
            // A bare mirror shows the value the stop is given elsewhere.
            val shown = if (appendText && value.isEmpty()) known[number].orEmpty() else value
            if (appendText) text.append(shown)
            if (nested) return
            if (number == 0) {
                marks += Mark(0, text.length - shown.length, text.length)
                if (end == null) {
                    template.append(END)
                    end = text.length
                } else {
                    // IntelliJ has one end. VS Code puts a cursor on every `$0`; the others are
                    // variables the template never stops at, for a caret to be added at each.
                    val name = finalName(finals.size + 1)
                    template.append('$').append(name).append('$')
                    finals += TabStop(name, shown, emptyList())
                }
                return
            }
            marks += Mark(number, text.length - shown.length, text.length)
            template.append('$').append(name(number)).append('$')
            // A later occurrence mirrors the first, but a bare `$1` before `${1:value}` takes the value.
            if (values[number].isNullOrEmpty()) values[number] = value
        }

        private fun literal(character: Char, nested: Boolean) {
            text.append(character)
            if (!nested) template.append(if (character == '$') "$$" else character)
        }
    }

    /** A choice's options, split on commas that are not escaped. */
    private fun options(list: String): List<String> {
        val options = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < list.length) {
            when (val character = list[index]) {
                '\\' if index + 1 < list.length -> { current.append(list[index + 1]); index++ }
                ',' -> { options += current.toString(); current.clear() }
                else -> current.append(character)
            }
            index++
        }
        options += current.toString()
        return options
    }

    /**
     * The `}` matching the `{` at [open], skipping nested braces and escapes.
     *
     * Returns -1 when there is none, in which case the `$` was never a placeholder and stays literal.
     */
    private fun closing(snippet: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < snippet.length) {
            when {
                snippet[index] == '\\' -> index++
                snippet[index] == '{' -> depth++
                snippet[index] == '}' -> { depth--; if (depth == 0) return index }
            }
            index++
        }
        return -1
    }

    private fun isName(character: Char) = character.isLetterOrDigit() || character == '_'
}
