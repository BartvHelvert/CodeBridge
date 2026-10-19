package me.helvert.codebridge.json

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import me.helvert.codebridge.launch.LaunchVariables
import me.helvert.codebridge.workspace.CodeBridgeWorkspace

/**
 * `${...}` in the strings of `launch.json` and `tasks.json`, as VS Code's own configuration editing
 * completes them; see [LaunchVariables], which a run configuration's form offers too.
 */
internal class CodeBridgeVariableCompletion : CompletionContributor() {

    /** Opens the lookup on the `{` of `${` in a string, for [CodeBridgeVariableCompletion] to fill. */
    internal class AutoPopup : TypedHandlerDelegate() {
        override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
            // If it turns out to be another brace, nothing is offered and no lookup appears.
            if (charTyped != '{' || file !is JsonFile) return Result.CONTINUE
            val offset = editor.caretModel.offset
            if (offset == 0) return Result.CONTINUE
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            val position = file.findElementAt(offset - 1) ?: return Result.CONTINUE
            if (position.parent !is JsonStringLiteral || '$' !in position.text) return Result.CONTINUE
            if (!isVariableFile(file.virtualFile?.name, position)) return Result.CONTINUE
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? JsonFile ?: return
        if (!isVariableFile(file.viewProvider.virtualFile.name, parameters.position)) return
        val literal = parameters.position.parent as? JsonStringLiteral ?: return
        val before = file.text.substring(literal.textRange.startOffset + 1, parameters.offset.coerceAtLeast(literal.textRange.startOffset + 1))
        val typing = LaunchVariables.typing(before) ?: return
        if (!LaunchVariables.complete(typing, result) { inputIds(file, parameters.position) }) return
        // Nothing else belongs inside a variable: the schema would offer the property's own values.
        result.stopHere()
    }

    /**
     * The `id` of each entry of the `inputs` [element] can use: the file's own, at the top, or in a
     * `.code-workspace` file those of the `launch` or `tasks` it is in.
     */
    private fun inputIds(file: JsonFile, element: PsiElement): List<String> {
        val holder = WORKSPACE_SECTIONS.firstNotNullOfOrNull { section(element, it) } ?: file.topLevelValue as? JsonObject
        val inputs = holder?.findProperty("inputs")?.value as? JsonArray ?: return emptyList()
        return inputs.valueList.mapNotNull { ((it as? JsonObject)?.findProperty("id")?.value as? JsonStringLiteral)?.value }
    }

    private companion object {
        val FILES = setOf("launch.json", "tasks.json")
        val WORKSPACE_SECTIONS = listOf("launch", "tasks")

        /**
         * Whether [element] is where VS Code resolves variables: anywhere in `.vscode/launch.json` and
         * `.vscode/tasks.json`, and in a `.code-workspace` file's `launch` and `tasks`.
         */
        fun isVariableFile(name: String?, element: PsiElement): Boolean {
            val parent = element.containingFile?.originalFile?.virtualFile?.parent
            if (name in FILES && parent?.name == ".vscode") return true
            if (name?.endsWith(".${CodeBridgeWorkspace.EXTENSION}") != true) return false
            return WORKSPACE_SECTIONS.any { section(element, it) != null }
        }

        /** The value of the top-level [name] property of a `.code-workspace` file that holds [element]. */
        fun section(element: PsiElement, name: String): JsonObject? {
            val root = (element.containingFile as? JsonFile)?.topLevelValue as? JsonObject ?: return null
            val value = root.findProperty(name)?.value as? JsonObject ?: return null
            return value.takeIf { PsiTreeUtil.isAncestor(it, element, false) }
        }
    }
}
