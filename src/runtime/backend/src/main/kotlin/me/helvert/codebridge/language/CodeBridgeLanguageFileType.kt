package me.helvert.codebridge.language

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLikeFileType
import me.helvert.codebridge.icons.FileIcons
import me.helvert.codebridge.manifest.LanguageContribution
import javax.swing.Icon

/**
 * The contributed language's identity in the editor: `CodeBridge.<id>`, under the language's own name.
 *
 * The name is namespaced because these are made up at runtime from whatever an extension declares,
 * and must not read as one of the platform's own types. The display name is the one the extension
 * gave the language, which is what puts "Rust" rather than "TextMate" or "Plain text" in the status
 * bar and the Override File Type list.
 *
 * [language] is the IDE language that reads the file, never one invented for the occasion: the
 * TextMate language where a grammar was found for the name, plain text otherwise. Everything an
 * editor does that this plugin does not do itself -- syntax highlighting, brace matching,
 * commenting, spell checking, PSI at all -- is registered against a language, so a language of our
 * own would be a file type nothing could answer for. Claiming the file is only worth doing if what
 * already read it keeps reading it.
 *
 * Secondary, deliberately: this is one of many types for that language, and must not become the
 * type the platform offers when something asks what a TextMate or plain text file looks like.
 *
 * The icon is chosen the way VS Code chooses one: its default file icon theme first -- [FileIcons],
 * keyed by the language's id and what it claims -- then [icon], the one the extension declared,
 * which VS Code too reads only as a fallback for a language the theme has none for. A language with
 * neither gets a drawn [CodeBridgeMonogram] rather than the plain text icon every one of them would
 * otherwise share.
 */
internal class CodeBridgeLanguageFileType(
    contribution: LanguageContribution,
    language: Language,
    private val icon: CodeBridgeLanguageIcon = CodeBridgeLanguageIcon.NONE,
) : LanguageFileType(language, /* secondary = */ true), PlainTextLikeFileType {
    private val typeName = "CodeBridge.${contribution.id}"
    private val label = contribution.aliases.firstOrNull { it.isNotBlank() } ?: contribution.id
    private val extension = contribution.extensions.firstOrNull()?.removePrefix(".").orEmpty()

    /** Built once: the platform asks for an icon on every repaint of every row showing one. */
    val monogram = CodeBridgeMonogram(label, contribution.id)

    /** VS Code's own icon for the language, or null if its theme has none. */
    val themed: Icon? by lazy {
        FileIcons.forLanguage(
            contribution.id,
            contribution.extensions,
            contribution.filenames
        )
    }

    override fun getName(): String = typeName
    override fun getDisplayName(): String = label
    override fun getDescription(): String = "$label (VS Code)"
    override fun getDefaultExtension(): String = extension
    override fun getIcon(): Icon? = themed ?: icon.current() ?: monogram.current()
}
