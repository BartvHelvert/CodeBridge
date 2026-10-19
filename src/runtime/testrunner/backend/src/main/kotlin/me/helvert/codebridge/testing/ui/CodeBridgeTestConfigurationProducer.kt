package me.helvert.codebridge.testing.ui

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.testing.HostedTesting

/**
 * Run and Debug for the tests a context names: the nodes selected in a test run's tree, or the test
 * at the caret. What fills the test tree's context menu, Rerun of a single test included, and makes
 * Ctrl+Shift+F10 run the test under the caret.
 */
class CodeBridgeTestConfigurationProducer : LazyRunConfigurationProducer<CodeBridgeTestRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(CodeBridgeTestConfigurationType::class.java).configurationFactories.single()

    override fun setupConfigurationFromContext(
        configuration: CodeBridgeTestRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val tests = tests(context).ifEmpty { return false }
        configuration.testIds = tests.map { it.first }
        configuration.name = if (tests.size == 1) tests.single().second else "${tests.first().second} and ${tests.size - 1} more"
        return true
    }

    override fun isConfigurationFromContext(configuration: CodeBridgeTestRunConfiguration, context: ConfigurationContext): Boolean {
        val tests = tests(context)
        return tests.isNotEmpty() && configuration.testIds == tests.map { it.first }
    }

    /** The ids and names of the tests [context] names, selected nodes first, else the caret's. */
    private fun tests(context: ConfigurationContext): List<Pair<String, String>> {
        val data = context.dataContext
        val proxies = AbstractTestProxy.DATA_KEYS.getData(data) ?: AbstractTestProxy.DATA_KEY.getData(data)?.let { arrayOf(it) }
        if (proxies != null) {
            return proxies.mapNotNull { proxy -> CodeBridgeTestLocator.testId(proxy.locationUrl)?.let { it to proxy.name } }.distinctBy { it.first }
        }
        return listOfNotNull(atCaret(context))
    }

    /** The innermost test whose range holds the caret, in a file an extension placed tests in. */
    private fun atCaret(context: ConfigurationContext): Pair<String, String>? {
        val editor = CommonDataKeys.EDITOR.getData(context.dataContext) ?: return null
        val file = context.location?.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val key = runCatching { UriComponents.key(file.toNioPath()) }.getOrNull() ?: return null
        val caret = editor.caretModel.logicalPosition
        val line = caret.line + 1
        val column = caret.column + 1
        val test = HostedTesting.getInstance(context.project).itemsIn(key)
            .filter { item ->
                val range = item.range ?: return@filter false
                (line > range.startLineNumber || line == range.startLineNumber && column >= range.startColumn) &&
                    (line < range.endLineNumber || line == range.endLineNumber && column <= range.endColumn)
            }
            // Innermost: the one that starts last, and of those the one that ends first.
            .maxWithOrNull(compareBy<Testing.Item>({ it.range!!.startLineNumber }, { it.range!!.startColumn })
                .thenByDescending { it.range!!.endLineNumber }.thenByDescending { it.range!!.endColumn })
            ?: return null
        return test.extId to CodeBridgeTestEvents.withoutIcons(test.label).ifEmpty { Testing.TestId.localId(test.extId) }
    }
}
