package me.helvert.codebridge.testing.ui

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.Printable
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.testing.HostedTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A run's events, read back by the IDE's own test runner: the service messages are only right if
 * the tree it builds from them is.
 */
class CodeBridgeTestEventsTest {

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

    private val file = UriComponents("file", "", "/w/a.test.ts", "", "")

    private fun item(extId: String, label: String = Testing.TestId.localId(extId), line: Int = 1) =
        Testing.Item(
            extId,
            label,
            file,
            Range(line, 1, line + 2, 2),
            null,
            null,
            null,
            emptyList(),
            false,
            JsonObject(emptyMap())
        )

    private fun id(vararg path: String) = Testing.TestId.join("ctrl", *path)

    /** The tree the test runner builds from what [events] are turned into. */
    private fun tree(suites: Set<String>, events: List<HostedTesting.Run.Event>): SMTestProxy.SMRootTestProxy {
        val root = SMTestProxy.SMRootTestProxy()
        runInEdtAndWait {
            val processor = GeneralIdBasedToSMTRunnerEventsConvertor(fixture.project, root, "VS Code")
            val converter = OutputToGeneralTestEventsConverter("VS Code", false, true, false)
            @Suppress("UsePropertyAccessSyntax")
            converter.setProcessor(processor)
            converter.onStartTesting()
            val translator =
                CodeBridgeTestEvents(
                    { it in suites }, { converter.process(it + "\n", ProcessOutputTypes.STDOUT) },
                    { converter.process(it, ProcessOutputTypes.STDOUT) })
            translator.started()
            events.forEach(translator::accept)
            converter.flushBufferOnProcessTermination(0)
            converter.finishTesting()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            processor.dispose(); converter.dispose()
        }
        return root
    }

    private fun SMTestProxy.child(name: String): SMTestProxy = children.single { it.name == name }

    /** What the console shows when [this] is selected. */
    private fun SMTestProxy.shown(): String {
        val out = StringBuilder()
        printOn(object : Printer {
            override fun print(text: String, contentType: ConsoleViewContentType) {
                out.append(text)
            }

            override fun onNewAvailable(printable: Printable) = printable.printOn(this)
            override fun printHyperlink(text: String, info: HyperlinkInfo?) {
                out.append(text)
            }

            override fun mark() = Unit
        })
        return out.toString()
    }

    @Test
    fun `output for no test goes to the run, not to whichever test is running`() {
        val file = id("c.rs")
        val panics = id("c.rs", "panics")
        val diff = id("c.rs", "diff")
        val root = tree(
            setOf(file), listOf(
                HostedTesting.Run.Event.Added(listOf(item(file), item(panics), item(diff))),
                HostedTesting.Run.Event.State(diff, Testing.State.RUNNING, null, emptyList()),
                HostedTesting.Run.Event.State(panics, Testing.State.RUNNING, null, emptyList()),
                HostedTesting.Run.Event.Output("diff says hi\r\n", diff),
                HostedTesting.Run.Event.Output("thread 'panics' panicked\r\n", null),
                HostedTesting.Run.Event.State(
                    panics,
                    Testing.State.FAILED,
                    null,
                    listOf(Testing.Message("panicked", null, null, null, null, emptyList()))
                ),
                HostedTesting.Run.Event.State(diff, Testing.State.PASSED, null, emptyList()),
                HostedTesting.Run.Event.Ended(null),
            )
        )
        val suite = root.child("c.rs")
        assertTrue(suite.child("diff").shown().contains("diff says hi"))
        assertFalse(suite.child("diff").shown().contains("thread 'panics'"), suite.child("diff").shown())
        assertFalse(suite.child("panics").shown().contains("thread 'panics'"))
        assertTrue(root.shown().contains("thread 'panics' panicked"), root.shown())
    }

    @Test
    fun `builds the tree the run reports, without the controller's root`() {
        val file = id("a.test.ts")
        val suite = id("a.test.ts", "math")
        val adds = id("a.test.ts", "math", "adds")
        val subtracts = id("a.test.ts", "math", "subtracts")
        val skipped = id("a.test.ts", "math", "later")
        val root = tree(
            setOf(file, suite), listOf(
                HostedTesting.Run.Event.TaskStarted("Mocha"),
                HostedTesting.Run.Event.Added(
                    listOf(
                        item("ctrl", "Mocha"),
                        item(file),
                        item(suite),
                        item(adds, "$(beaker) adds", 4)
                    )
                ),
                HostedTesting.Run.Event.State(adds, Testing.State.RUNNING, null, emptyList()),
                HostedTesting.Run.Event.Output("adding\r\n", adds),
                HostedTesting.Run.Event.State(adds, Testing.State.PASSED, 12.0, emptyList()),
                HostedTesting.Run.Event.Added(listOf(item(subtracts, line = 9))),
                HostedTesting.Run.Event.State(
                    subtracts, Testing.State.FAILED, 3.0, listOf(
                        Testing.Message(
                            "expected 1 to equal 2", "2", "1", uri = this.file, range = Range(10, 5, 10, 9),
                            stackTrace = listOf(
                                Testing.Frame(
                                    "Context.<anonymous>",
                                    this.file,
                                    me.helvert.codebridge.exthost.protocol.payload.Position(10, 5)
                                )
                            ),
                        )
                    )
                ),
                HostedTesting.Run.Event.Added(listOf(item(skipped))),
                HostedTesting.Run.Event.State(skipped, Testing.State.SKIPPED, null, emptyList()),
                HostedTesting.Run.Event.Ended(null),
            )
        )

        val math = root.child("a.test.ts").child("math")
        assertTrue(math.isSuite)
        val passed = math.child("adds")
        assertTrue(passed.isPassed, "Passed, and named without its codicon")
        assertEquals(12L, passed.duration)
        assertTrue(passed.locationUrl!!.startsWith("codebridgetest://"))
        assertEquals(adds, CodeBridgeTestLocator.testId(passed.locationUrl))

        val failed = math.child("subtracts")
        assertTrue(failed.isDefect)
        assertEquals("expected 1 to equal 2", failed.errorMessage)
        assertTrue(failed.stacktrace!!.contains("Context.<anonymous>"), failed.stacktrace)
        assertTrue(math.child("later").isIgnored)
    }

    @Test
    fun `a suite's own failure is reported when it closes, and a test the run never finished is skipped`() {
        val file = id("b.test.ts")
        val first = id("b.test.ts", "first")
        val hanging = id("b.test.ts", "hanging")
        val root = tree(
            setOf(file), listOf(
                HostedTesting.Run.Event.Added(listOf(item(file), item(first), item(hanging))),
                HostedTesting.Run.Event.State(file, Testing.State.RUNNING, null, emptyList()),
                HostedTesting.Run.Event.State(first, Testing.State.PASSED, null, emptyList()),
                HostedTesting.Run.Event.State(hanging, Testing.State.RUNNING, null, emptyList()),
                HostedTesting.Run.Event.State(
                    file,
                    Testing.State.ERRORED,
                    null,
                    listOf(Testing.Message("Cannot find module", null, null, null, null, emptyList()))
                ),
                HostedTesting.Run.Event.Ended("Cancelled"),
            )
        )
        val suite = root.child("b.test.ts")
        assertTrue(suite.child("first").isPassed)
        assertTrue(suite.child("hanging").isIgnored)
        assertTrue(suite.isDefect)
        assertEquals("Cannot find module", suite.errorMessage)
    }
}
