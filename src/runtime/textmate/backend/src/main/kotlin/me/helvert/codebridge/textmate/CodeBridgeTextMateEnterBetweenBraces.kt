package me.helvert.codebridge.textmate

import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.textmate.editor.TextMateEditorUtils

/**
 * Puts the closing bracket on a line of its own when Enter is pressed between a pair, as VS Code
 * does with `fn foo() {|}`.
 *
 * The platform does this for every language in `EnterBetweenBracesFinalHandler`, but only when the
 * two brackets are different PSI elements -- its default test for "both inside one string literal".
 * A TextMate file is a single leaf, so every pair failed it and Enter left the closing bracket on
 * the caret's line, indented along with it. TextMate registers no delegate of its own.
 *
 * Which characters pair is a question about the scope the caret is in, so [isBracePair] lets every
 * pair through and [bracesAreInTheSameElement] answers it from the brackets the extension declares
 * in its language configuration.
 */
internal class CodeBridgeTextMateEnterBetweenBraces : EnterBetweenBracesDelegate() {
    override fun isBracePair(lBrace: Char, rBrace: Char): Boolean = true

    override fun bracesAreInTheSameElement(
        file: PsiFile,
        editor: Editor,
        lBraceOffset: Int,
        rBraceOffset: Int
    ): Boolean {
        val scope = (editor as? EditorEx)?.let(TextMateEditorUtils::getCurrentScopeSelector) ?: return true
        val text = editor.document.charsSequence
        val pair = TextMateEditorUtils.findRightHighlightingPair(lBraceOffset, text, scope) ?: return true
        return !text.startsWith(pair.right, rBraceOffset)
    }
}
