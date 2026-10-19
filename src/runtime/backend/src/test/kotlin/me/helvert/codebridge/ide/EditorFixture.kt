package me.helvert.codebridge.ide

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * A real IDE, driven from JUnit 5.
 *
 * The platform's own base classes are JUnit 3, which would make these the only tests in the project
 * written differently from the rest. Driving the fixture's lifecycle directly costs a few lines and
 * keeps one test style.
 *
 * Everything touching the editor runs on the EDT, which is why [onEdt] exists rather than the
 * implicit dispatch a `BasePlatformTestCase` would give.
 */
abstract class EditorFixture {

    protected lateinit var fixture: CodeInsightTestFixture
        private set

    @BeforeEach
    fun startIde() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val project = factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge")
        fixture = factory.createCodeInsightFixture(project.fixture, factory.createTempDirTestFixture())
        onEdt { fixture.setUp() }
    }

    @AfterEach
    fun stopIde() {
        onEdt { fixture.tearDown() }
    }

    /**
     * Runs on the EDT, and does not fail on errors other plugins log.
     *
     * `idea.load.plugins.id` in the test task keeps other plugins out, which is the real fix -- one
     * of them raised `NoClassDefFoundError` from a background thread and failed whichever test
     * happened to be running. This stays as a second line of defence for anything the platform
     * itself logs during a light fixture's startup. Errors from `me.helvert` still fail.
     */
    protected fun onEdt(block: () -> Unit) = LoggedErrorProcessor.executeWith<Throwable>(
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<String>,
                t: Throwable?
            ): Set<Action> {
                val ours = category.contains("helvert") || message.contains("helvert") ||
                        t?.stackTrace?.any { it.className.startsWith("me.helvert") } == true
                return if (ours) Action.ALL else Action.NONE
            }
        },
    ) { runInEdtAndWait(block) }
}
