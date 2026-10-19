package me.helvert.codebridge.workspace

/**
 * `launch.json`, `tasks.json` and `settings.json` are not plain JSON: VS Code parses them as
 * JSON with Comments, which additionally allows `//` and block comments and a trailing comma
 * before a closing `}` or `]`.
 *
 * [stripToJson] rewrites those constructs to whitespace rather than deleting them, so every
 * offset and line number in the result still matches the original document. That keeps parser
 * error positions usable when we later want to report problems against the file in the editor.
 */
object Jsonc {

    fun stripToJson(text: String): String {
        val out = CharArray(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            when (val c = text[i]) {
                '"' -> {
                    out[i] = c
                    i++
                    while (i < n) {
                        val s = text[i]
                        out[i] = s
                        i++
                        if (s == '\\') {
                            if (i < n) {
                                out[i] = text[i]
                                i++
                            }
                        } else if (s == '"') {
                            break
                        }
                    }
                }

                '/' if i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') {
                        out[i] = ' '
                        i++
                    }
                }

                '/' if i + 1 < n && text[i + 1] == '*' -> {
                    out[i] = ' '
                    out[i + 1] = ' '
                    i += 2
                    while (i < n) {
                        if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            break
                        }
                        // Newlines survive so that line numbers keep pointing at the original text.
                        out[i] = if (text[i] == '\n') '\n' else ' '
                        i++
                    }
                }

                else -> {
                    out[i] = c
                    i++
                }
            }
        }
        blankTrailingCommas(out)
        return String(out)
    }

    private fun blankTrailingCommas(buf: CharArray) {
        var i = 0
        val n = buf.size
        while (i < n) {
            val c = buf[i]
            if (c == '"') {
                i++
                while (i < n) {
                    val s = buf[i]
                    i++
                    if (s == '\\') {
                        i++
                    } else if (s == '"') {
                        break
                    }
                }
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < n && buf[j].isWhitespace()) j++
                if (j < n && (buf[j] == '}' || buf[j] == ']')) buf[i] = ' '
            }
            i++
        }
    }
}
