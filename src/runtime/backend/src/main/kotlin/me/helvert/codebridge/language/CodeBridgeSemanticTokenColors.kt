package me.helvert.codebridge.language

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import me.helvert.codebridge.exthost.protocol.payload.SemanticTokens

/**
 * What a semantic token is painted with.
 *
 * This is the step that decides how much of VS Code's richness survives the crossing, and it is
 * worth being precise about why it cannot simply be copied. A VS Code theme maps token types to
 * literal colours; an IntelliJ colour scheme has no slot for "parameter in a file some extension
 * contributed", so the mapping has to land on keys the user's scheme already has an opinion about.
 * That is not a loss the way the TextMate mapping is: `DefaultLanguageHighlighterColors` draws
 * almost exactly the distinctions LSP's standard token types do -- parameter from local variable,
 * instance field from static field, declaration from call -- because both are describing the same
 * facts about a program. What does not survive is a theme's *choice* of colour for them.
 *
 * Every key is minted here with a platform fallback rather than used directly, so a colour scheme
 * -- or a settings page, which is the obvious next thing -- can move one of them without moving
 * every IntelliJ language that shares the fallback.
 *
 * [keyFor] returns null for a type nothing here recognises. That is deliberate and it matters: a
 * token left undrawn keeps whatever the TextMate grammar gave it, so an unfamiliar vocabulary
 * degrades to the colouring that was already there instead of flattening it to plain identifiers.
 */
internal object CodeBridgeSemanticTokenColors {

    /**
     * Drawn over whatever colour the token already has, rather than instead of it.
     *
     * Strikethrough with no foreground of its own, so a deprecated method still reads as a method.
     */
    val DEPRECATED: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("CODEBRIDGE_SEMANTIC_DEPRECATED", CodeInsightColors.DEPRECATED_ATTRIBUTES)

    /**
     * The key for a token, or null when its type is not one of LSP's standard set.
     *
     * Modifiers refine rather than replace: the type chooses the family, and at most one modifier
     * picks a different member of it. They are applied most-specific first, because a token is
     * frequently several at once -- `console` is a variable that is both `defaultLibrary` and
     * `readonly`, and a static method declaration is `static` and `declaration` together.
     */
    fun keyFor(token: SemanticTokens.Token): TextAttributesKey? {
        val modifiers = token.modifiers
        // Anything from the standard library is the one distinction worth taking from the modifiers
        // before the type is consulted: it is what tells `Array` from a class someone declared and
        // `console` from a variable they did, and IntelliJ has a key that means exactly that.
        if (DEFAULT_LIBRARY in modifiers && token.type in PREDEFINED_TYPES) return PREDEFINED_SYMBOL
        return when (token.type) {
            "namespace", "type", "typeParameter" -> CLASS_REFERENCE
            "class", "struct", "enum" -> if (declares(modifiers)) CLASS_NAME else CLASS_REFERENCE
            "interface" -> INTERFACE_NAME
            "enumMember" -> CONSTANT

            "parameter" -> PARAMETER
            "variable" -> if (STATIC in modifiers) STATIC_FIELD else LOCAL_VARIABLE
            "property", "event" -> if (STATIC in modifiers) STATIC_FIELD else INSTANCE_FIELD

            "function" -> if (declares(modifiers)) FUNCTION_DECLARATION else FUNCTION_CALL
            "method" -> if (STATIC in modifiers) STATIC_METHOD else INSTANCE_METHOD
            "macro", "decorator" -> METADATA
            "label" -> LABEL

            // A grammar usually has these right already. They are mapped anyway, because a language
            // whose extension ships no grammar at all has nothing else colouring it.
            "keyword", "modifier" -> KEYWORD
            "comment" -> COMMENT
            "string", "regexp" -> STRING
            "number" -> NUMBER
            "operator" -> OPERATION_SIGN

            else -> null
        }
    }

    /** A name being introduced rather than used; `definition` is the same idea spelled differently. */
    private fun declares(modifiers: Set<String>): Boolean =
        DECLARATION in modifiers || DEFINITION in modifiers

    private const val DECLARATION = "declaration"
    private const val DEFINITION = "definition"
    private const val STATIC = "static"
    private const val DEFAULT_LIBRARY = "defaultLibrary"

    /**
     * Where `defaultLibrary` is worth saying out loud.
     *
     * Not methods or properties: a member of a library type is still read as a member, and VS Code's
     * own themes leave those alone too.
     */
    private val PREDEFINED_TYPES =
        setOf("variable", "function", "class", "interface", "struct", "enum", "type", "namespace")

    private fun key(name: String, fallback: TextAttributesKey): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("CODEBRIDGE_SEMANTIC_$name", fallback)

    private val PREDEFINED_SYMBOL = key("PREDEFINED_SYMBOL", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    private val CLASS_NAME = key("CLASS_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)
    private val CLASS_REFERENCE = key("CLASS_REFERENCE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
    private val INTERFACE_NAME = key("INTERFACE_NAME", DefaultLanguageHighlighterColors.INTERFACE_NAME)
    private val CONSTANT = key("CONSTANT", DefaultLanguageHighlighterColors.CONSTANT)
    private val PARAMETER = key("PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
    private val LOCAL_VARIABLE = key("LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    private val INSTANCE_FIELD = key("INSTANCE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    private val STATIC_FIELD = key("STATIC_FIELD", DefaultLanguageHighlighterColors.STATIC_FIELD)
    private val INSTANCE_METHOD = key("INSTANCE_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    private val STATIC_METHOD = key("STATIC_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD)
    private val FUNCTION_DECLARATION =
        key("FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    private val FUNCTION_CALL = key("FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    private val METADATA = key("METADATA", DefaultLanguageHighlighterColors.METADATA)
    private val LABEL = key("LABEL", DefaultLanguageHighlighterColors.LABEL)
    private val KEYWORD = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    private val COMMENT = key("COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    private val STRING = key("STRING", DefaultLanguageHighlighterColors.STRING)
    private val NUMBER = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    private val OPERATION_SIGN = key("OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN)
}
