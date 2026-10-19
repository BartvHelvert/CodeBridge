package me.helvert.codebridge.language

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import me.helvert.codebridge.icons.FileIcons
import javax.swing.Icon

/**
 * VS Code's file icon for a file, by its name, where the IDE has no type of its own for the file.
 *
 * VS Code's icon theme looks at the file name before it looks at any language: the whole name, then
 * the extension. That is how a fresh VS Code draws `Cargo.toml` with a config icon although
 * nothing in it knows TOML. A file type icon cannot do that here, because a file gets one of this
 * plugin's types only when an installed extension claims its language; everything else keeps the
 * type the IDE gave it.
 *
 * Only for files the IDE does not really know: unknown, plain text, TextMate's catch-all type, and
 * the types this plugin made for a claimed language. A file the IDE reads itself -- `.toml` with the
 * TOML plugin enabled, `.java`, `.json` -- keeps the IDE's icon, which belongs to the support that
 * actually reads it. For a claimed file the name is asked first, as VS Code asks it first; a name
 * the theme does not know falls through to the type's own icon, which goes by the language.
 */
internal class CodeBridgeFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.isDirectory || !isUntyped(file.fileType)) return null
        return FileIcons.forFile(file.name)
    }

    private fun isUntyped(type: FileType): Boolean =
        type === UnknownFileType.INSTANCE ||
                type === PlainTextFileType.INSTANCE ||
                type is CodeBridgeLanguageFileType ||
                // By name: this module does not depend on the TextMate plugin, and its type is a single
                // instance named this.
                type.name == TEXTMATE

    private companion object {
        const val TEXTMATE = "textmate"
    }
}
