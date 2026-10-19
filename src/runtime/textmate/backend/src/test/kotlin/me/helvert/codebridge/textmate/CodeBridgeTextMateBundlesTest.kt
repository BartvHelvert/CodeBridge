package me.helvert.codebridge.textmate

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import me.helvert.codebridge.extensions.ExtensionInstallationService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodeBridgeTextMateBundlesTest {
    @Test
    fun `bundle discovery follows install replacement disable and uninstall`() = runInEdtAndWait {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory()
            .createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-textmate").fixture
        fixture.setUp()
        try {
            val service = ExtensionInstallationService.getInstance()
            val provider = CodeBridgeTextMateBundles()
            val vsix = Path.of(checkNotNull(System.getProperty("codebridge.textmate.vsix")))
            val installed = service.install(vsix) {}.extension
            try {
                val bundle = provider.getBundles().single { it.path == installed.directory }
                assertEquals("CodeBridge TextMate Probe", bundle.name)
                assertTrue(service.setEnabled(installed.id, false))
                assertTrue(provider.getBundles().none { it.path == installed.directory })
                assertTrue(service.setEnabled(installed.id, true))
                assertEquals(bundle, provider.getBundles().single { it.path == installed.directory })

                val replacement = service.install(vsix) {}.extension
                assertNotEquals(installed.directory, replacement.directory)
                assertTrue(provider.getBundles().none { it.path == installed.directory })
                assertEquals(bundle.name, provider.getBundles().single { it.path == replacement.directory }.name)
                assertTrue(service.uninstall(installed.id))
                assertTrue(provider.getBundles().none { it.path == replacement.directory })
            } finally {
                service.uninstall(installed.id)
            }
        } finally {
            fixture.tearDown()
        }
    }
}
