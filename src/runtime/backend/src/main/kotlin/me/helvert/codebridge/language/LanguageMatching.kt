package me.helvert.codebridge.language

import me.helvert.codebridge.manifest.LanguageContribution

/**
 * Whether a contributed language claims a file, by name: a file name named outright, a
 * `filenamePatterns` glob, or an extension.
 *
 * A pattern with a `/` is about the file's path, which a name alone cannot answer, and is not read;
 * nor are VS Code's per-user `files.associations`.
 */
internal fun LanguageContribution.matches(name: String): Boolean = closeness(name) != null

/**
 * How closely this language claims [name], higher being closer, or null if it does not: a file name
 * named outright, then the longest pattern, then the longest extension -- VS Code's order, which
 * makes `tsconfig.json` `jsonc` although `.json` is `json`.
 */
internal fun LanguageContribution.closeness(name: String): Int? = when {
    name in filenames -> Int.MAX_VALUE
    else -> filenamePatterns.filter { '/' !in it && glob(it).matches(name) }.maxOfOrNull { PATTERN + it.length }
        ?: extensions.filter { name.endsWith(it, ignoreCase = true) }.maxOfOrNull { it.length }
}

/** The ids of the languages in [languages] that claim [name], the closest first -- the one it is opened as. */
internal fun closest(languages: List<LanguageContribution>, name: String): Set<String> = languages
    .mapNotNull { language -> language.closeness(name)?.let { language.id to it } }
    .sortedByDescending { it.second }
    .map { it.first }.toSet()

/** Above any extension's length, below a file name named outright. */
private const val PATTERN = 1 shl 16

/**
 * A VS Code glob as a regular expression over a file name: `*` and `?` within the name, `{a,b}`
 * alternatives, `[…]` classes, and `**` as anything. Case is ignored, as extensions' is.
 */
internal fun glob(pattern: String): Regex {
    val regex = StringBuilder()
    var index = 0
    var braces = 0
    while (index < pattern.length) {
        when (val char = pattern[index]) {
            '*' -> if (pattern.startsWith("**", index)) {
                regex.append(".*"); index++
            } else regex.append("[^/]*")

            '?' -> regex.append("[^/]")
            '{' -> {
                braces++; regex.append("(?:")
            }

            '}' -> if (braces > 0) {
                braces--; regex.append(')')
            } else regex.append("\\}")

            ',' -> regex.append(if (braces > 0) "|" else ",")
            '[' -> {
                val end = pattern.indexOf(']', index + 1)
                if (end < 0) regex.append("\\[")
                else {
                    // `[!…]` is a glob's negation, `[^…]` a regular expression's.
                    val members = pattern.substring(index + 1, end).replace("\\", "\\\\")
                    regex.append('[').append(if (members.startsWith('!')) "^" + members.drop(1) else members)
                        .append(']')
                    index = end
                }
            }

            else -> regex.append(Regex.escape(char.toString()))
        }
        index++
    }
    return Regex(regex.toString(), RegexOption.IGNORE_CASE)
}
