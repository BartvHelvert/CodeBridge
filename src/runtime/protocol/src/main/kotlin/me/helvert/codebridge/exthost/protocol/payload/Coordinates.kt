package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * A caret position in VS Code's editor model: **1-based** line and column.
 *
 * Not LSP's 0-based positions. What crosses this protocol is the workbench's own editor model, and
 * the extension's language client converts to and from LSP on the far side; a position that is off
 * by one in either direction produces an answer about the wrong token rather than an error.
 */
data class Position(val lineNumber: Int, val column: Int) {

    fun toJson(): JsonObject = buildJsonObject {
        put("lineNumber", lineNumber)
        put("column", column)
    }
}

/** A span in the same 1-based coordinates, inclusive of the start and exclusive of the end column. */
data class Range(
    val startLineNumber: Int,
    val startColumn: Int,
    val endLineNumber: Int,
    val endColumn: Int,
) {

    fun toJson(): JsonObject = buildJsonObject {
        put("startLineNumber", startLineNumber)
        put("startColumn", startColumn)
        put("endLineNumber", endLineNumber)
        put("endColumn", endColumn)
    }

    companion object {

        /**
         * Reads a range, which arrives either bare or as an `{insert, replace}` pair.
         *
         * The insert range is the one taken: it runs from the start of the word to the caret, which
         * is exactly the prefix a completion is matched against. `replace` would additionally
         * swallow the rest of the word the caret sits in -- VS Code's overtype behaviour, which has
         * no equivalent in IntelliJ.
         */
        fun from(element: JsonElement?): Range? {
            val range = element as? JsonObject ?: return null
            val bare = range["insert"]?.jsonObject ?: range
            val start = bare["startLineNumber"]?.jsonPrimitive?.intOrNull ?: return null
            return Range(
                startLineNumber = start,
                startColumn = bare["startColumn"]?.jsonPrimitive?.intOrNull ?: 1,
                endLineNumber = bare["endLineNumber"]?.jsonPrimitive?.intOrNull ?: start,
                endColumn = bare["endColumn"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
    }
}
