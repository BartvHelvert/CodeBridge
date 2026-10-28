package me.helvert.codebridge.textmate

import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesDelegate
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import me.helvert.codebridge.extensions.ExtensionInstallationService
import org.jetbrains.plugins.textmate.TextMateLanguage
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Enter between brackets the extension declares opens a line between them, and between characters
 * it does not declare it is an ordinary line break.
 */
class CodeBridgeTextMateEnterBetweenBracesTest {
    @Test
    fun `enter between declared brackets puts the closing one on its own line`() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-enter").fixture,
        )
        onEdt { fixture.setUp() }
        val service = ExtensionInstallationService.getInstance()
        try {
            onEdt {
                // The light fixture doesn't load CodeBridge's descriptor.
                TextMateBundleProvider.EP_NAME.point.registerExtension(CodeBridgeTextMateBundles(), fixture.testRootDisposable)
                registerFileLanguage(fixture.testRootDisposable)
                val delegate = LanguageExtensionPoint<EnterBetweenBracesDelegate>(
                    TextMateLanguage.LANGUAGE.id, CodeBridgeTextMateEnterBetweenBraces(),
                )
                // The point reads a descriptor even for a bean that carries its instance.
                delegate.pluginDescriptor = DefaultPluginDescriptor("me.helvert.codebridge")
                DELEGATES.point.registerExtension(delegate, fixture.testRootDisposable)
            }
            service.install(Path.of(checkNotNull(System.getProperty("codebridge.textmate.vsix")))) {}
            awaitBundle(present = true)

            fun enter(before: String): String {
                lateinit var after: String
                onEdt {
                    fixture.configureByText("example.codebridgegrammar", before)
                    fixture.type('\n')
                    val text = fixture.editor.document.text
                    val caret = fixture.editor.caretModel.offset
                    after = text.substring(0, caret) + "<caret>" + text.substring(caret)
                }
                return after
            }

            val split = enter("fn foo() {<caret>}").lines()
            assertEquals(3, split.size, split.joinToString("\n"))
            assertEquals("fn foo() {", split[0])
            assertEquals("<caret>", split[1].trim())
            assertEquals("}", split[2])

            assertEquals(2, enter("fn foo() a<caret>b").lines().size)
        } finally {
            service.uninstall("codebridge.textmate-probe")
            awaitBundle(present = false)
            onEdt { fixture.tearDown() }
        }
    }

    /**
     * Waits for the bundle reload an install or uninstall queued; see `CodeBridgeTextMateFileTypeTest`.
     * Quiet on timeout, because it also runs in a `finally`.
     */
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
        private val DELEGATES =
            ExtensionPointName<LanguageExtensionPoint<EnterBetweenBracesDelegate>>("com.intellij.enterBetweenBracesDelegate")
    }
}
