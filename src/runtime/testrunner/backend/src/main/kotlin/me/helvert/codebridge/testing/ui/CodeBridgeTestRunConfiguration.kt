package me.helvert.codebridge.testing.ui

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.testing.HostedTesting
import me.helvert.codebridge.testing.TestSessionConsoles
import org.jdom.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.JComponent

class CodeBridgeTestConfigurationType : ConfigurationTypeBase(
    ID, "VS Code tests", "Tests VS Code extensions declare with the testing API, run by the extension",
    AllIcons.RunConfigurations.TestState.Run,
) {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId(): String = ID
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            CodeBridgeTestRunConfiguration(project, this)
    }

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    companion object {
        const val ID = "CodeBridge.Tests"
    }
}

/**
 * Tests an extension declared, by their test ids -- every controller's tests, for none -- run by the
 * extension's own run profile: its default Run profile, or Debug profile under the Debug executor.
 * The console is the IDE's test runner; see [CodeBridgeTestEvents].
 */
class CodeBridgeTestRunConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<Any>(project, factory, "VS Code tests") {

    var testIds: List<String> = emptyList()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Editor()

    override fun checkConfiguration() {
        if (HostedTesting.getInstance(project).controllers().isEmpty())
            throw RuntimeConfigurationException("No VS Code extension has declared tests yet")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        CodeBridgeTestRunState(environment, this, testIds)

    override fun readExternal(element: Element) {
        super.readExternal(element)
        // Ids hold NUL, which XML cannot.
        testIds = element.getChildren(TEST).mapNotNull { it.getAttributeValue("id") }
            .map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        testIds.forEach {
            element.addContent(
                Element(TEST).setAttribute(
                    "id",
                    URLEncoder.encode(it, StandardCharsets.UTF_8)
                )
            )
        }
    }

    private companion object {
        const val TEST = "test"
    }

    /** Read-only: the tests are the extension's, chosen from the gutter. */
    private inner class Editor : SettingsEditor<CodeBridgeTestRunConfiguration>() {
        private val tests = JBLabel()
        private val panel = FormBuilder.createFormBuilder().addLabeledComponent("Tests:", tests).panel

        override fun resetEditorFrom(configuration: CodeBridgeTestRunConfiguration) {
            val testing = HostedTesting.getInstance(configuration.project)
            tests.text = if (configuration.testIds.isEmpty()) "All tests of every extension"
            else configuration.testIds.joinToString(", ") { id ->
                testing.item(id)?.label ?: Testing.TestId.localId(id)
            }
        }

        override fun applyEditorTo(configuration: CodeBridgeTestRunConfiguration) = Unit
        override fun createEditor(): JComponent = panel
    }
}

/**
 * Starts the run when the console is ready -- or attaches to one the extension started itself, the
 * first time ([ATTACH]); Rerun then runs the same tests anew.
 */
class CodeBridgeTestRunState(
    private val environment: ExecutionEnvironment,
    private val configuration: RunConfiguration,
    private val testIds: List<String>,
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = environment.project
        val testing = HostedTesting.getInstance(project)
        val attach = environment.getUserData(ATTACH)
        environment.putUserData(ATTACH, null)
        val group = if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) Testing.Group.DEBUG else Testing.Group.RUN
        val handler = CodeBridgeTestProcessHandler(testing::hasChildren) { attach ?: testing.run(testIds, group) }
        val properties = CodeBridgeTestConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(FRAMEWORK, handler, properties)
        return DefaultExecutionResult(console, handler).apply {
            setRestartActions(properties.createRerunFailedTestsAction(console as ConsoleView))
        }
    }

    companion object {
        const val FRAMEWORK: String = "VS Code"

        /** A run already under way, to show rather than start; consumed by the first execution. */
        val ATTACH: Key<HostedTesting.Run> = Key.create("codebridge.tests.attach")
    }
}

class CodeBridgeTestConsoleProperties(configuration: RunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, CodeBridgeTestRunState.FRAMEWORK, executor) {

    init {
        isIdBasedTestTree = true
    }

    override fun getTestLocator(): SMTestLocator = CodeBridgeTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        CodeBridgeRerunFailedTests(consoleView, this)
}

/** Reruns what failed, by the test ids their location hints carry. */
private class CodeBridgeRerunFailedTests(container: ComponentContainer, properties: TestConsoleProperties) :
    AbstractRerunFailedTestsAction(container) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? RunConfigurationBase<*> ?: return null
        val failed =
            getFailedTests(configuration.project).mapNotNull { CodeBridgeTestLocator.testId(it.locationUrl) }.distinct()
        if (failed.isEmpty()) return null
        val peer = (configuration as? MyRunProfile)?.peer ?: configuration
        return object : MyRunProfile(peer) {
            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
                CodeBridgeTestRunState(environment, this, failed)
        }
    }
}

/**
 * Debug for these configurations. The extension's debug profile starts the debugger itself, through
 * `vscode.debug.startDebugging`, and that session's tab shows the tests, as a debugged JUnit run's
 * does; see [TestSessionConsoles]. A run that starts no session shows them in a tab of its own once
 * it ends.
 */
class CodeBridgeTestDebugRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "CodeBridge.TestDebug"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && isCodeBridgeTests(profile)

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val result = state.execute(environment.executor, this) ?: return null
        val handler = result.processHandler ?: return RunContentBuilder(
            result,
            environment
        ).showRunContent(environment.contentToReuse)
        val offer = TestSessionConsoles.offer(environment.project, result.executionConsole)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                if (!offer.withdraw()) return
                Scope.getInstance(environment.project).scope.launch(Dispatchers.EDT) {
                    val descriptor = RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
                    RunContentManager.getInstance(environment.project).showRunContent(environment.executor, descriptor)
                }
            }
        })
        // Started here: with no tab of its own, nothing else starts it.
        handler.startNotify()
        return null
    }

    /** Where the run tab is shown: once the process has ended, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        fun isCodeBridgeTests(profile: RunProfile): Boolean =
            profile is CodeBridgeTestRunConfiguration || (profile as? WrappingRunConfiguration<*>)?.peer is CodeBridgeTestRunConfiguration
    }
}
