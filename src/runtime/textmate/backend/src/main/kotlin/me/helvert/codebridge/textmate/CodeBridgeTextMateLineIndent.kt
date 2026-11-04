package me.helvert.codebridge.textmate

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.IndentInfo
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import org.jetbrains.plugins.textmate.TextMateLanguage
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.editor.TextMateEditorUtils
import org.jetbrains.plugins.textmate.editor.Utils.Companion.indentOfLine
import org.jetbrains.plugins.textmate.language.preferences.IndentAction
import org.jetbrains.plugins.textmate.language.preferences.IndentationRules
import java.util.concurrent.ConcurrentHashMap

/**
 * Indents the line Enter opens from the language configuration's `onEnterRules` and
 * `indentationRules`, read as the JavaScript regular expressions they are.
 *
 * TextMate's own `TextMateLineIndentProvider` does the same, but compiles the patterns with
 * `java.util.regex`, so one that JavaScript accepts and Java does not -- Go's
 * `increaseIndentPattern` has a bare `{` -- failed with a SEVERE on every Enter, and the line was
 * not indented. Ordered first, this takes over for every TextMate file; the rules are the platform's,
 * except that a pattern is searched for in the line, as VS Code's `RegExp.test` does, rather than
 * matched against all of it.
 */
internal class CodeBridgeTextMateLineIndent : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language is TextMateLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (language !is TextMateLanguage) return null
        val scope = (editor as? EditorEx)?.let(TextMateEditorUtils::getCurrentScopeSelector) ?: return null
        val file = editor.virtualFile ?: return null
        val preferences = TextMateService.getInstance().preferenceRegistry.getPreferences(scope)

        val document = editor.document
        val line = document.getLineNumber(offset)
        if (line <= 0 || document.getLineStartOffset(line) <= 0) return null
        val previous = document.charsSequence
            .subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()

        val change = indentChange(
            previous,
            preferences.fold(IndentationRules.empty()) { rules, it -> rules.updateWith(it.indentationRules) },
            preferences.flatMap { it.onEnterRules.orEmpty() }.map { it.beforeText.text to it.action.indent },
        ) ?: return null
        val options = CodeStyle.getSettings(project, file).getIndentOptionsByFile(project, file, null)
        val indent = maxOf(previous.indentOfLine(options) + change * options.TAB_SIZE, 0)
        return IndentInfo(0, indent, 0).generateNewWhiteSpace(options)
    }

    /** +1 or -1 tab stops for the line after [previous], or null to leave it to the platform. */
    private fun indentChange(
        previous: String,
        rules: IndentationRules,
        onEnter: List<Pair<String, IndentAction>>,
    ): Int? {
        for ((before, action) in onEnter) {
            if (regex(before)?.containsMatchIn(previous) != true) continue
            when (action) {
                IndentAction.INDENT -> return 1
                IndentAction.NONE -> return null
                else -> {}
            }
        }
        if (rules.increaseIndentPattern?.let(::regex)?.containsMatchIn(previous) == true) return 1
        if (rules.decreaseIndentPattern?.let(::regex)?.containsMatchIn(previous) == true) return -1
        return null
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeTextMateLineIndent::class.java)

        /** By the extension's own pattern; null for one that does not compile even rewritten, logged once. */
        private val compiled = ConcurrentHashMap<String, Result<Regex>>()

        fun regex(pattern: String): Regex? = compiled.computeIfAbsent(pattern) {
            runCatching { Regex(JavaScriptRegex.toJava(pattern)) }
                .onFailure { failure -> LOG.warn("Indentation pattern '$pattern' is not usable", failure) }
        }.getOrNull()
    }
}
