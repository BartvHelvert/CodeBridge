package me.helvert.codebridge.textmate

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.impl.LiveTemplateCompletionContributor
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import me.helvert.codebridge.extensions.ExtensionInstallationService
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * An extension's snippet in a TextMate file is offered once: CodeBridge's, not also TextMate's copy
 * read from the same package.
 */
class CodeBridgeTextMateSnippetsTest {

    @Test
    fun `a contributed snippet shows once in a TextMate file, and inserts as VS Code would`() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-snippets").fixture,
        )
        onEdt { fixture.setUp() }
        val service = ExtensionInstallationService.getInstance()
        val autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        try {
            onEdt {
                // The light fixture doesn't load CodeBridge's descriptor.
                TextMateBundleProvider.EP_NAME.point.registerExtension(CodeBridgeTextMateBundles(), fixture.testRootDisposable)
                registerFileLanguage(fixture.testRootDisposable)
                CONTRIBUTORS.point.registerExtension(
                    CompletionContributorEP("any", "me.helvert.codebridge.language.CodeBridgeSnippetCompletion", DESCRIPTOR),
                    fixture.testRootDisposable,
                )
                CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
                TemplateManagerImpl.setTemplateTesting(fixture.testRootDisposable)
                // Live templates are kept out of completion in tests unless asked for.
                LiveTemplateCompletionContributor.setShowTemplatesInTests(true, fixture.testRootDisposable)
            }
            service.install(Path.of(checkNotNull(System.getProperty("codebridge.textmate.vsix")))) {}
            awaitBundle(present = true)

            fun offered(): List<String> {
                lateinit var offered: List<String>
                onEdt {
                    fixture.configureByText("example.codebridgegrammar", "probeb<caret>")
                    fixture.completeBasic()
                    offered = fixture.lookupElementStrings.orEmpty()
                }
                return offered
            }

            // Without the filter, TextMate offers its copy too -- the duplicate there is to remove.
            assertEquals(listOf("probeblock", "probeblock"), offered(), "TextMate no longer offers bundle snippets")

            val filter = Disposer.newDisposable(fixture.testRootDisposable)
            onEdt {
                CONTRIBUTORS.point.registerExtension(
                    CompletionContributorEP("textmate", CodeBridgeTextMateSnippets::class.java.name, DESCRIPTOR),
                    LoadingOrder.readOrder("first, before liveTemplates"),
                    filter,
                )
            }
            assertEquals(listOf("probeblock"), offered())

            onEdt {
                fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
                TemplateManagerImpl.getTemplateState(fixture.editor)!!.gotoEnd(false)
                fixture.checkResult("PROBE name {\n    <caret>\n}")
            }
        } finally {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            service.uninstall("codebridge.textmate-probe")
            awaitBundle(present = false)
            onEdt { fixture.tearDown() }
        }
    }

    @Test
    fun `the filter runs before the live-template contributor whose items it drops`() {
        val descriptor = java.nio.file.Files.readString(
            Path.of("src", "main", "resources", "CodeBridge.textmate.backend.xml").toAbsolutePath()
        )
        assertEquals(
            true,
            Regex("""<completion\.contributor language="textmate" order="first, before liveTemplates"\s+implementationClass="me\.helvert\.codebridge\.textmate\.CodeBridgeTextMateSnippets"/>""")
                .containsMatchIn(descriptor),
            "Nothing in a light fixture would notice this being deleted",
        )
    }

    /** Waits for the bundle reload an install or uninstall queued; see `CodeBridgeTextMateFileTypeTest`. */
    private fun awaitBundle(present: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if ((TextMateService.getInstance()
                    .getLanguageDescriptorByFileName("example.codebridgegrammar") != null) == present
            ) return
            Thread.sleep(50)
        }
    }

    private fun onEdt(block: () -> Unit) = runInEdtAndWait(block)

    private companion object {
        val CONTRIBUTORS = ExtensionPointName<CompletionContributorEP>("com.intellij.completion.contributor")
        val DESCRIPTOR = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge"), CodeBridgeTextMateSnippets::class.java.classLoader,
        )
    }
}
