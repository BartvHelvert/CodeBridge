package me.helvert.codebridge.testing.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.testing.HostedTesting
import java.util.*
import javax.swing.Icon

/**
 * Run and Debug in the gutter, on the line each test an extension placed in a file starts on, as
 * VS Code draws them; the icon says how the tests there ended last time.
 *
 * Placed by offset rather than by element: a file whose language comes from a TextMate grammar, or
 * from nothing, is a single PSI leaf, and its tests are all inside it. Asking for the file's tests
 * is also what asks the extension to discover them; see [HostedTesting.itemsIn].
 */
class CodeBridgeTestLineMarkers : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val first = elements.firstOrNull() ?: return
        val psiFile = first.containingFile ?: return
        val file = psiFile.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return
        val project = first.project
        val testing = HostedTesting.getInstance(project)
        val key = runCatching { UriComponents.key(file.toNioPath()) }.getOrNull() ?: return
        val tests = testing.itemsIn(key).filter { it.range != null }
        if (tests.isEmpty()) return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        // Where each line's tests start, the line's own first test deciding where.
        val starts = TreeMap<Int, MutableList<Testing.Item>>()
        for ((line, onLine) in tests.groupBy { it.range!!.startLineNumber }) {
            if (line < 1 || line > document.lineCount) continue
            val lineStart = document.getLineStartOffset(line - 1)
            val lineEnd = document.getLineEndOffset(line - 1)
            val column = onLine.minOf { it.range!!.startColumn }
            val offset = (lineStart + column - 1).coerceIn(lineStart, maxOf(lineStart, lineEnd - 1))
            starts.getOrPut(offset) { mutableListOf() } += onLine
        }
        for (element in elements) {
            if (element.firstChild != null) continue
            val range = element.textRange
            for ((offset, onLine) in starts.subMap(range.startOffset, true, range.endOffset, range.isEmpty)) {
                result.add(marker(project, element, offset, onLine.sortedBy { it.range!!.startColumn }, testing))
            }
        }
    }

    private fun marker(project: Project, element: PsiElement, offset: Int, tests: List<Testing.Item>, testing: HostedTesting): LineMarkerInfo<PsiElement> {
        val results = tests.map { testing.lastResult(it.extId) }
        val icon = when {
            results.any { it == Testing.State.FAILED || it == Testing.State.ERRORED } -> AllIcons.RunConfigurations.TestState.Red2
            results.all { it == Testing.State.PASSED } -> AllIcons.RunConfigurations.TestState.Green2
            tests.any { testing.hasChildren(it.extId) } -> AllIcons.RunConfigurations.TestState.Run_run
            else -> AllIcons.RunConfigurations.TestState.Run
        }
        val actions = DefaultActionGroup()
        val debuggable = testing.canRun(tests.map { it.extId }, Testing.Group.DEBUG)
        for ((extId, label) in tests) {
            val name = CodeBridgeTestEvents.withoutIcons(label).ifEmpty { Testing.TestId.localId(extId) }
            actions.add(RunTest(project, extId, name, DefaultRunExecutor.getRunExecutorInstance(), "Run '$name'", AllIcons.Actions.Execute))
            if (debuggable) actions.add(RunTest(project, extId, name, DefaultDebugExecutor.getDebugExecutorInstance(), "Debug '$name'", AllIcons.Actions.StartDebugger))
        }
        val tooltip = Tooltip(tests.joinToString("\n") { test ->
            val name = CodeBridgeTestEvents.withoutIcons(test.label)
            when (testing.lastResult(test.extId)) {
                Testing.State.PASSED -> "$name passed"
                Testing.State.FAILED, Testing.State.ERRORED -> "$name failed"
                Testing.State.SKIPPED -> "$name was skipped"
                else -> "Run $name"
            }
        }, offset)
        return Marker(element, TextRange(offset, offset + 1).intersection(element.textRange) ?: TextRange(offset, offset), icon, tooltip, actions)
    }

    /**
     * Equal for the same text at the same place: the platform keeps a marker it already drew when
     * the new one equals it, and in a single-leaf file every marker has the same element.
     */
    private data class Tooltip(val text: String, val offset: Int) : com.intellij.util.Function<PsiElement, String> {
        override fun `fun`(element: PsiElement): String = text
    }

    private class Marker(element: PsiElement, range: TextRange, icon: Icon, tooltip: Tooltip, private val actions: DefaultActionGroup) :
        LineMarkerInfo<PsiElement>(element, range, icon, tooltip, null, GutterIconRenderer.Alignment.CENTER, { tooltip.text }) {
        override fun createGutterRenderer(): GutterIconRenderer = object : LineMarkerGutterIconRenderer<PsiElement>(this) {
            override fun getClickAction(): AnAction? = null
            override fun isNavigateAction(): Boolean = true
            override fun getPopupMenuActions(): ActionGroup = actions
        }
    }

    private class RunTest(
        private val project: Project,
        private val extId: String,
        private val name: String,
        private val executor: Executor,
        text: String,
        icon: Icon,
    ) : AnAction(text, null, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(event: AnActionEvent) {
            CodeBridgeTestRuns.start(project, listOf(extId), name, executor)
        }
    }
}
