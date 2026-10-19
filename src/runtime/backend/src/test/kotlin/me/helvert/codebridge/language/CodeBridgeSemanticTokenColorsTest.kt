package me.helvert.codebridge.language

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import me.helvert.codebridge.exthost.protocol.payload.SemanticTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which colour-scheme key each of LSP's standard token types lands on.
 *
 * `SemanticHighlightingTest` draws a few tokens through a real editor; this is the whole table, so
 * a type or modifier that stops being recognised shows up here rather than as a token quietly
 * keeping its TextMate colour.
 */
class CodeBridgeSemanticTokenColorsTest {

    private fun key(type: String, vararg modifiers: String): String? =
        CodeBridgeSemanticTokenColors.keyFor(SemanticTokens.Token(0, 0, 1, type, modifiers.toSet()))?.externalName

    @Test
    fun `each standard type has a key`() {
        val expected = mapOf(
            "namespace" to "CLASS_REFERENCE", "type" to "CLASS_REFERENCE", "typeParameter" to "CLASS_REFERENCE",
            "class" to "CLASS_REFERENCE", "struct" to "CLASS_REFERENCE", "enum" to "CLASS_REFERENCE",
            "interface" to "INTERFACE_NAME", "enumMember" to "CONSTANT",
            "parameter" to "PARAMETER", "variable" to "LOCAL_VARIABLE",
            "property" to "INSTANCE_FIELD", "event" to "INSTANCE_FIELD",
            "function" to "FUNCTION_CALL", "method" to "INSTANCE_METHOD",
            "macro" to "METADATA", "decorator" to "METADATA", "label" to "LABEL",
            "keyword" to "KEYWORD", "modifier" to "KEYWORD", "comment" to "COMMENT",
            "string" to "STRING", "regexp" to "STRING", "number" to "NUMBER", "operator" to "OPERATION_SIGN",
        )
        for ((type, name) in expected) assertEquals("CODEBRIDGE_SEMANTIC_$name", key(type), "Token type '$type'")
    }

    @Test
    fun `a type nothing recognises is left to the grammar`() {
        assertNull(key("lifetime"))
        assertNull(key("selfKeyword", "declaration"))
        assertNull(key(""))
    }

    @Test
    fun `declaring a name is told from using it, whichever word the server uses`() {
        assertEquals("CODEBRIDGE_SEMANTIC_CLASS_NAME", key("class", "declaration"))
        assertEquals("CODEBRIDGE_SEMANTIC_CLASS_NAME", key("struct", "definition"))
        assertEquals("CODEBRIDGE_SEMANTIC_CLASS_NAME", key("enum", "declaration"))
        assertEquals("CODEBRIDGE_SEMANTIC_FUNCTION_DECLARATION", key("function", "declaration"))
        assertEquals("CODEBRIDGE_SEMANTIC_FUNCTION_DECLARATION", key("function", "definition"))
        // Not a family that has a declaration key of its own.
        assertEquals("CODEBRIDGE_SEMANTIC_PARAMETER", key("parameter", "declaration"))
    }

    @Test
    fun `static picks the static member of a family`() {
        assertEquals("CODEBRIDGE_SEMANTIC_STATIC_FIELD", key("variable", "static"))
        assertEquals("CODEBRIDGE_SEMANTIC_STATIC_FIELD", key("property", "static"))
        assertEquals("CODEBRIDGE_SEMANTIC_STATIC_FIELD", key("event", "static"))
        assertEquals("CODEBRIDGE_SEMANTIC_STATIC_METHOD", key("method", "static", "declaration"))
    }

    @Test
    fun `the standard library is marked for what it names, but not for its members`() {
        for (type in listOf("variable", "function", "class", "interface", "struct", "enum", "type", "namespace")) {
            assertEquals("CODEBRIDGE_SEMANTIC_PREDEFINED_SYMBOL", key(type, "defaultLibrary"), "Token type '$type'")
        }
        // `console` is a variable that is both; the library is what is worth saying.
        assertEquals("CODEBRIDGE_SEMANTIC_PREDEFINED_SYMBOL", key("variable", "defaultLibrary", "readonly", "static"))
        // A member of a library type still reads as a member.
        assertEquals("CODEBRIDGE_SEMANTIC_INSTANCE_METHOD", key("method", "defaultLibrary"))
        assertEquals("CODEBRIDGE_SEMANTIC_INSTANCE_FIELD", key("property", "defaultLibrary"))
        assertEquals("CODEBRIDGE_SEMANTIC_PARAMETER", key("parameter", "defaultLibrary"))
    }

    @Test
    fun `modifiers no rule reads change nothing`() {
        assertEquals(key("method"), key("method", "async", "readonly", "modification"))
        assertEquals(key("function"), key("function", "deprecated"))
    }

    @Test
    fun `each key falls back to the platform key of the same meaning`() {
        val token = SemanticTokens.Token(0, 0, 1, "parameter", emptySet())
        assertSame(DefaultLanguageHighlighterColors.PARAMETER, CodeBridgeSemanticTokenColors.keyFor(token)?.fallbackAttributeKey)
        val comment = SemanticTokens.Token(0, 0, 1, "comment", emptySet())
        assertSame(DefaultLanguageHighlighterColors.LINE_COMMENT, CodeBridgeSemanticTokenColors.keyFor(comment)?.fallbackAttributeKey)
        assertEquals("CODEBRIDGE_SEMANTIC_DEPRECATED", CodeBridgeSemanticTokenColors.DEPRECATED.externalName)
    }
}
