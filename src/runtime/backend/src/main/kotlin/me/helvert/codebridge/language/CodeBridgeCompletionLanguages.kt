package me.helvert.codebridge.language

import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.Disposer
import org.jetbrains.annotations.TestOnly

/**
 * Registers [CodeBridgeCompletionContributor] for the languages the IDE gives claimed files.
 *
 * Diagnostics need none of this: the annotator EP resolves through `allForLanguageOrAny`, so one
 * declaration with an empty `language` covers everything. `CompletionContributor.forLanguage` does
 * not -- it resolves by exact key through `LanguageExtension.forKey`, which widens to meta languages
 * and stops there. Every language has to be named, and which ones matter is not known until an
 * extension is installed: an extension contributing `.json` or `.py` leaves the file with the IDE's
 * own language, where the static `TEXT` registration is never asked.
 *
 * So the languages are learned rather than declared, from the files that are actually claimed. A
 * manifest pattern cannot answer this on its own: `FileTypeManager.getFileTypeByFileName` consults
 * name matchers only, and a file with a TextMate grammar is claimed through
 * `FileTypeIdentifiableByVirtualFile`, which that path never reaches. The opened file knows.
 *
 * `TEXT` is left out, being declared in `CodeBridge.backend.xml`; registering it again would offer
 * every item twice.
 *
 * Application-level deliberately. The extension store is application-wide and so is an extension
 * point, so a project-level owner would register the same contributor once per open project and
 * every lookup would show its items twice.
 */
@Service
internal class CodeBridgeCompletionLanguages : Disposable {

    private val registered = mutableSetOf<String>()
    private var owner = Disposer.newDisposable(this, GENERATION)

    /**
     * Registers completion for the language of [fileType], if it has one and is new.
     *
     * Called with a file an enabled extension claims, from the worker that attaches it, which is
     * well before anyone can ask for a completion in it. A [CodeBridgeLanguageFileType] names the language
     * that reads the file rather than the contributed one, and that is the right key: the PSI, and
     * so the lookup, is that language's.
     *
     * The set only grows, and deliberately. An extension being removed does not make a stale
     * registration wrong: [CodeBridgeCompletionContributor] returns immediately unless some extension has
     * offered to complete, and [HostedLanguageFeatures] has no providers to ask once the host is
     * gone. It is bounded by the languages of files the user actually opened.
     */
    @Synchronized
    fun observe(fileType: FileType) {
        val language = (fileType as? LanguageFileType)?.language ?: return
        if (language.id == PlainTextLanguage.INSTANCE.id) return
        if (!registered.add(language.id)) return
        EP.point.registerExtension(CompletionContributorEP(language.id, CONTRIBUTOR, DESCRIPTOR), owner)
        LOG.debug("Completion registered for contributed language ${language.id}")
    }

    /** Application-scoped registrations outlive a light fixture, and would follow the next test. */
    @TestOnly
    @Synchronized
    fun forgetForTest() {
        registered.clear()
        Disposer.dispose(owner)
        owner = Disposer.newDisposable(this, GENERATION)
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeCompletionLanguages::class.java)

        private val EP = ExtensionPointName<CompletionContributorEP>("com.intellij.completion.contributor")

        private val CONTRIBUTOR = CodeBridgeCompletionContributor::class.java.name

        /**
         * Our own class loader, not the one the main plugin descriptor carries.
         *
         * The bean resolves `implementationClass` through the descriptor it was given, and the
         * contributor lives in `lib/modules/CodeBridge.backend.jar`, which the main plugin's loader
         * does not see.
         */
        private val DESCRIPTOR = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge.runtime"),
            CodeBridgeCompletionContributor::class.java.classLoader,
        )

        private const val GENERATION = "CodeBridge completion languages"

        fun getInstance(): CodeBridgeCompletionLanguages =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(CodeBridgeCompletionLanguages::class.java)
    }
}
