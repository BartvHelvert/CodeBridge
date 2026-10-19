package me.helvert.codebridge.textmate

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import me.helvert.codebridge.language.CodeBridgeFileLanguageProvider
import org.jetbrains.plugins.textmate.TextMateLanguage
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateEditorHighlighterProvider

/**
 * Hands a claimed file back to TextMate to read.
 *
 * TextMate would have taken the file itself -- `TextMateFileType` claims every name it has a
 * grammar for, before anything this plugin could do about it -- and then the editor calls a Rust
 * file a TextMate file. The file type is worth taking back for the name alone, but only if nothing
 * is lost with it, and what TextMate does for a file it does under two different keys.
 *
 * By language, which a claimed file keeps: the syntax highlighter is registered for `textmate` and
 * resolves the grammar from the file's own name, so it lands on the same file, with the same
 * grammar, under the contributed language's name. Parsing, commenting and spelling come the same
 * way.
 *
 * By file type name, which a claimed file does not keep -- that is the whole point of claiming it
 * -- and which [claimed] puts back.
 *
 * Asked for every name an extension claims, including ones no bundle has a grammar for, which is
 * what null is for.
 */
internal class CodeBridgeTextMateFileLanguage : CodeBridgeFileLanguageProvider {
    override fun languageFor(fileName: String): Language? =
        TextMateService.getInstance().getLanguageDescriptorByFileName(fileName)?.let { TextMateLanguage.LANGUAGE }

    override fun claimed(fileType: FileType) {
        ApplicationManager.getApplication().getService(CodeBridgeTextMateFileTypes::class.java).register(fileType)
    }
}

/**
 * Registers TextMate's editor highlighter for a file type it does not know the name of.
 *
 * The provider is the part that cannot be reached by language. `editorHighlighterProvider` is keyed
 * by file type name, and without TextMate's own the platform builds the default lexer highlighter,
 * which packs each token type into a short -- TextMate mints a token type per scope and overruns
 * that, so the editor fails with "token type ... is not registered" rather than merely losing
 * colour. The names cannot be declared because they are the languages the user's extensions
 * contribute, so the registration is made when the file type is.
 *
 * Application-level, holding its own registrations, for the same reason
 * `CodeBridgeCompletionLanguages` is: an extension point is application-wide, and one owner per open
 * project would register the same provider several times over.
 */
@Service(Service.Level.APP)
internal class CodeBridgeTextMateFileTypes : Disposable {
    private val registered = mutableSetOf<String>()

    @Synchronized
    fun register(fileType: FileType) {
        // Marked first: the point notifies its listeners on this thread, and those type open files
        // again, which can ask for this same registration before this one has returned.
        if (!registered.add(fileType.name)) return
        val bean = FileTypeExtensionPoint<EditorHighlighterProvider>(fileType.name, TextMateEditorHighlighterProvider())
        // The bean carries the provider itself, but the point still reads its descriptor to decide
        // how it would have loaded one, and rejects a bean that has none.
        bean.pluginDescriptor = DESCRIPTOR
        try {
            HIGHLIGHTERS.point.registerExtension(bean, DESCRIPTOR, this)
        } catch (e: Throwable) {
            // A registration that failed is tried again next time.
            registered -= fileType.name
            throw e
        }
        LOG.debug("TextMate will highlight ${fileType.name}")
    }

    override fun dispose() = Unit

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeTextMateFileTypes::class.java)

        private val HIGHLIGHTERS =
            ExtensionPointName<FileTypeExtensionPoint<EditorHighlighterProvider>>("com.intellij.editorHighlighterProvider")

        /**
         * Only to satisfy the point, which will not take a bean without one.
         *
         * Nothing is ever loaded through it -- the bean is registered holding the provider itself
         * -- but it names TextMate's loader anyway, which is the one that would have to answer if
         * that ever changed.
         */
        private val DESCRIPTOR = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge.runtime"),
            TextMateEditorHighlighterProvider::class.java.classLoader,
        )
    }
}
