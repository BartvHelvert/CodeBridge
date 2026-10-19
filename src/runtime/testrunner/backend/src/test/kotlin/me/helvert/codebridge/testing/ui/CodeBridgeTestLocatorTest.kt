package me.helvert.codebridge.testing.ui

import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Jump to Source from a test run's tree: the hint a run reports, back to the test's line. */
class CodeBridgeTestLocatorTest {

    private lateinit var fixture: CodeInsightTestFixture

    @BeforeEach
    fun startIde() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val project = factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-tests")
        fixture = factory.createCodeInsightFixture(project.fixture, factory.createTempDirTestFixture())
        runInEdtAndWait { fixture.setUp() }
    }

    @AfterEach
    fun stopIde() {
        runInEdtAndWait { fixture.tearDown() }
    }

    @Test
    fun `a reported test opens at its own line, in a file spelled as the extension spells it`() {
        val path = Files.createTempDirectory("codebridge-locator").resolve("shapes.rs")
        Files.writeString(path, "mod tests {\n    #[test]\n    fn rectangle_area() {}\n}\n")
        val file = assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

        // rust-analyzer's spelling on Windows: `file:///c:/...`, lowercase drive.
        val uri = UriComponents.of(path)
            .let { it.copy(path = Regex("^/([A-Z]):").replace(it.path) { m -> "/${m.groupValues[1].lowercase()}:" }) }
        val item = Testing.Item(
            "ctrl\u0000tests\u0000rectangle_area", "rectangle_area", uri, Range(3, 8, 3, 22),
            null, null, null, emptyList(), false, JsonObject(emptyMap())
        )
        val hint = assertNotNull(CodeBridgeTestEvents.locationHint(item))
        assertEquals(item.extId, CodeBridgeTestLocator.testId(hint))

        val location = ReadAction.computeBlocking<com.intellij.execution.Location<*>?, Throwable> {
            CodeBridgeTestLocator.getLocation(
                CodeBridgeTestLocator.PROTOCOL, hint.substringAfter("://"), fixture.project,
                GlobalSearchScope.allScope(fixture.project)
            ).singleOrNull()
        }
        val descriptor = assertNotNull(assertNotNull(location).openFileDescriptor)
        assertEquals(file, descriptor.file)
        assertEquals(2, descriptor.line)
        assertEquals(7, descriptor.column)

        // Module tests load no plugin descriptor: what the module registers is registered here.
        ExtensionTestUtil.maskExtensions(
            ConfigurationType.CONFIGURATION_TYPE_EP,
            ConfigurationType.CONFIGURATION_TYPE_EP.extensionList + CodeBridgeTestConfigurationType(),
            fixture.testRootDisposable
        )
        ExtensionTestUtil.maskExtensions(
            RunConfigurationProducer.EP_NAME,
            RunConfigurationProducer.EP_NAME.extensionList + CodeBridgeTestConfigurationProducer(), fixture.testRootDisposable
        )

        // A node of the run's tree, right-clicked: what the context menu's Run and Debug are made from.
        val proxy = com.intellij.execution.testframework.sm.runner.SMTestProxy("rectangle_area", false, hint)
        val configuration =
            ReadAction.computeBlocking<com.intellij.execution.RunnerAndConfigurationSettings?, Throwable> {
                val data = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, fixture.project)
                    .add(com.intellij.execution.Location.DATA_KEY, location)
                    .add(com.intellij.execution.testframework.AbstractTestProxy.DATA_KEY, proxy)
                    .build()
                com.intellij.execution.actions.ConfigurationContext.getFromContext(data, "test").configuration
            }
        val tests = assertNotNull(configuration).configuration as CodeBridgeTestRunConfiguration
        assertEquals(listOf(item.extId), tests.testIds)
        assertEquals("rectangle_area", tests.name)
    }
}
