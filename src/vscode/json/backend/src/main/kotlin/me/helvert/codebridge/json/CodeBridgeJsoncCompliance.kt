package me.helvert.codebridge.json

import com.intellij.json.codeinsight.JsonStandardComplianceProvider
import com.intellij.json.psi.JsonElement
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.workspace.CodeBridgeWorkspace

/**
 * Comments and trailing commas where VS Code allows them: the files it reads as JSON with comments
 * -- `.vscode/launch.json`, `tasks.json` and `settings.json`, the user settings, and `.code-workspace`
 * files. Its own templates write comments there, so strict JSON's complaint would only be noise.
 */
internal class CodeBridgeJsoncCompliance : JsonStandardComplianceProvider() {
    override fun isCommentAllowed(comment: PsiComment): Boolean = isJsonc(comment)
    override fun isTrailingCommaAllowed(el: JsonElement): Boolean = isJsonc(el)

    private fun isJsonc(element: PsiElement): Boolean =
        element.containingFile?.originalFile?.virtualFile?.let(::isJsonc) == true

    companion object {
        private val VSCODE_FILES = setOf("launch.json", "tasks.json", "settings.json")

        fun isJsonc(file: VirtualFile): Boolean =
            file.name in VSCODE_FILES && file.parent?.name == ".vscode" ||
                    file.extension == CodeBridgeWorkspace.EXTENSION ||
                    CodeBridgeUserSettings.isFile(file.path)
    }
}
