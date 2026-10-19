package me.helvert.codebridge.language

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.VirtualFile

/**
 * Where the platform is told that an installed extension answers for this file.
 *
 * The first thing `getFileTypeByFile` consults, ahead of the special file types the TextMate plugin
 * claims through and ahead of content detection, which is the only position from which a file type
 * decided by what the user installed can win. [CodeBridgeLanguageFileTypes] holds the decision and the
 * cache that makes it cheap enough to sit on a path the platform walks for every file it sees.
 *
 * Public only so that the TextMate module's tests can stand this up beside their own registrations;
 * the IDE reaches it through `CodeBridge.backend.xml`.
 */
class CodeBridgeLanguageFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        // Directories are typed too, and no `contributes.languages` entry is about one.
        if (file.isDirectory) return null
        return CodeBridgeLanguageFileTypes.getInstance().forName(file.name)
    }
}
