package me.helvert.codebridge.language

import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileType

/**
 * Whatever already reads a file an installed extension claims.
 *
 * [CodeBridgeLanguageFileTypes] takes these files away from every other candidate, the TextMate plugin
 * included -- that is the point of it, because TextMate calls all of them `textmate` and the file
 * the user opened is a Rust file. Having taken the file it owes it everything the file had, and
 * asks here for it.
 *
 * A point rather than a call because the TextMate plugin is not in every IDE, and the module that
 * knows about it (`CodeBridge.textmate.backend`) is only loaded where it is. A file nothing answers for
 * falls back to plain text, which is what these files had before any of this existed.
 */
interface CodeBridgeFileLanguageProvider {
    /** The language for [fileName], or null when this provider has nothing to read it with. */
    fun languageFor(fileName: String): Language?

    /**
     * Registers whatever the new [fileType] needs that is keyed by file type rather than language.
     *
     * Called once per type minted for a name this provider answered [languageFor] for, and only
     * for the provider that answered. Editor support is split between the two keys and a claimed
     * file changes only one of them: the syntax highlighter is found by language and arrives for
     * free, the highlighter that drives it is found by file type name and does not. Registrations
     * made here outlive the call and belong to the implementor.
     */
    fun claimed(fileType: FileType) = Unit

    companion object {
        val EP_NAME: ExtensionPointName<CodeBridgeFileLanguageProvider> =
            ExtensionPointName("me.helvert.codebridge.fileLanguage")
    }
}
