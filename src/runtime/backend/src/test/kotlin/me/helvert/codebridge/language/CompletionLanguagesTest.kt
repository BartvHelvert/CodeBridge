package me.helvert.codebridge.language

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import me.helvert.codebridge.ide.EditorFixture
import me.helvert.codebridge.manifest.LanguageContribution
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion reaching a file the IDE already has a language for.
 *
 * Most extensions contribute an extension the IDE knows -- `.json`, `.py`, `.md` -- and such a file
 * keeps the IDE's own language rather than a [CodeBridgeLanguageFileType]. Diagnostics cope because the
 * annotator is registered for `Language.ANY`; `CompletionContributor.forLanguage` has no equivalent
 * and resolves by exact key, so until these registrations existed an extension contributing `.json`
 * offered completions that were asked for and never shown.
 */
class CompletionLanguagesTest : EditorFixture() {

    /**
     * The registrar is an application service, so its registrations outlive this fixture and would
     * follow every later test in the same JVM into its lookups.
     */
    @AfterEach
    fun clearRegistrations() = onEdt { CodeBridgeCompletionLanguages.getInstance().forgetForTest() }

    @Test
    fun `the language of a claimed file gets a contributor`() = onEdt {
        val json = requireNotNull(languageOf("probe.json")) { "The fixture needs JSON to be a real language" }
        assertTrue(ours(json).isEmpty(), "Nothing may be registered for JSON before a file is claimed")

        CodeBridgeCompletionLanguages.getInstance().observe(fileTypeOf("probe.json"))

        assertEquals(1, ours(json).size, "Completion must be registered for the language the IDE gives the file")
    }

    @Test
    fun `the same language twice registers once`() = onEdt {
        val languages = CodeBridgeCompletionLanguages.getInstance()
        val json = requireNotNull(languageOf("probe.json"))

        repeat(3) { languages.observe(fileTypeOf("probe.json")) }

        // Every duplicate would put the extension's items in the lookup a second time.
        assertEquals(1, ours(json).size, "Opening more files of one language must not stack contributors")
    }

    @Test
    fun `plain text is left to the descriptor`() = onEdt {
        // `CodeBridge.backend.xml` declares `TEXT` already, so registering it here would double
        // every item in the lookup.
        CodeBridgeCompletionLanguages.getInstance().observe(PlainTextFileType.INSTANCE)

        val text = requireNotNull(languageOf("probe.txt"))
        assertTrue(ours(text).isEmpty(), "TEXT is declared in the descriptor and must not be added again")
    }

    @Test
    fun `a claimed file is registered under the language that reads it`() = onEdt {
        // The file type is this plugin's, the language is whatever could already read the file --
        // TextMate's where a grammar was found for it. That one is what a lookup resolves through.
        val json = requireNotNull(languageOf("probe.json"))
        val type = CodeBridgeLanguageFileType(LanguageContribution(id = "probe"), json)

        CodeBridgeCompletionLanguages.getInstance().observe(type)

        assertEquals(1, ours(json).size, "The reading language is what a lookup in a claimed file asks")
    }

    @Test
    fun `a claimed file nothing else reads is left to the descriptor`() = onEdt {
        // A claimed name no grammar answered for is read as plain text, which `CodeBridge.backend.xml`
        // declares already; registering it here would double every item in the lookup.
        val type = CodeBridgeLanguageFileType(LanguageContribution(id = "probe"), PlainTextLanguage.INSTANCE)

        CodeBridgeCompletionLanguages.getInstance().observe(type)

        val text = requireNotNull(languageOf("probe.txt"))
        assertTrue(ours(text).isEmpty(), "TEXT is declared in the descriptor and must not be added again")
    }

    /** Our own registrations only: the fixture's JSON support brings contributors of its own. */
    private fun ours(language: Language): List<CompletionContributor> =
        CompletionContributor.forLanguage(language, null).filterIsInstance<CodeBridgeCompletionContributor>()

    private fun fileTypeOf(name: String) = FileTypeManager.getInstance().getFileTypeByFileName(name)

    private fun languageOf(name: String) = (fileTypeOf(name) as? LanguageFileType)?.language
}
