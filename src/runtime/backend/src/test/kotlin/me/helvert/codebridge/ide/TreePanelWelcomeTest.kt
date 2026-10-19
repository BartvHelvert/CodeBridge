package me.helvert.codebridge.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.views.TreeViewSource
import me.helvert.codebridge.views.CodeBridgeTreePanel
import org.junit.jupiter.api.Test
import java.awt.Component
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.text.Element
import javax.swing.text.html.HTMLDocument
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A tree view's welcome content when no items come back. GitLens, for one, registers its grouped
 * Source Control view only once the Continue link in that view's welcome content is clicked.
 */
class TreePanelWelcomeTest : EditorFixture() {

    @Test
    fun `a view no provider is registered for shows its welcome content`() =
        withPanel(TreeChildrenDto(problem = "No provider", unregistered = true)) { panel, _ ->
            awaitTrue { UIUtil.findComponentOfType(panel, JButton::class.java)?.let { shownIn(panel, it) } == true }
            assertEquals("Continue", assertNotNull(UIUtil.findComponentOfType(panel, JButton::class.java)).text)
        }

    @Test
    fun `codicons in welcome content are drawn, not spelled out`() =
        withPanel(
            TreeChildrenDto(),
            listOf(WelcomeLineDto(listOf(WelcomePartDto("$(loading~spin) Loading..."))))
        ) { panel, _ ->
            awaitTrue { UIUtil.findComponentOfType(panel, JEditorPane::class.java) != null }
            val pane = UIUtil.findComponentOfType(panel, JEditorPane::class.java)!!
            val document = pane.document as HTMLDocument
            assertEquals("Loading...", document.getText(0, document.length).trim())
            assertTrue(
                elements(document.defaultRootElement).any { it.name == "icon" },
                "The spinner stands where the reference was"
            )
        }

    @Test
    fun `a view whose items could not be read says why, over its welcome content`() =
        withPanel(TreeChildrenDto(problem = "Could not start probe.ext")) { panel, tree ->
            awaitTrue { tree.emptyText.text == "Could not start probe.ext" }
            assertEquals(true, shownIn(panel, tree))
        }

    private fun shownIn(panel: CodeBridgeTreePanel, component: Component): Boolean =
        generateSequence(component) { it.parent }.takeWhile { it !== panel }.all { it.isVisible }

    private fun elements(element: Element): Sequence<Element> =
        sequenceOf(element) + (0 until element.elementCount).asSequence().flatMap { elements(element.getElement(it)) }

    private suspend fun awaitTrue(condition: () -> Boolean) =
        withTimeout(5.seconds) { while (!condition()) delay(10.milliseconds) }

    private class Source(private val answer: TreeChildrenDto) : TreeViewSource {
        override suspend fun children(viewId: String, handle: String?) = answer
        override suspend fun visible(viewId: String, visible: Boolean) = Unit
        override suspend fun expanded(viewId: String, handle: String, expanded: Boolean) = Unit
        override suspend fun selection(viewId: String, handles: List<String>, focus: String?) = Unit
        override suspend fun run(viewId: String, handle: String) = Unit
        override suspend fun icon(key: String): ByteArray? = null
        override suspend fun itemActions(viewId: String, handle: String): List<TreeActionDto> = emptyList()
        override suspend fun runAction(viewId: String, actionId: Int, handle: String?, selection: List<String>) = Unit
        override suspend fun runWelcomeLink(viewId: String, link: Int) = Unit
        override suspend fun checkboxes(viewId: String, states: Map<String, Boolean>) = Unit
    }

    private fun withPanel(
        answer: TreeChildrenDto,
        welcome: List<WelcomeLineDto> = listOf(
            WelcomeLineDto(
                listOf(WelcomePartDto("Continue", link = 0)),
                button = true
            )
        ),
        test: suspend (CodeBridgeTreePanel, Tree) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            withContext(Dispatchers.EDT) {
                val panel = CodeBridgeTreePanel(
                    Source(answer),
                    scope,
                    TreeViewDto(
                        "grouped",
                        "Grouped",
                        "probe.ext",
                        "scm",
                        "Source Control",
                        welcome = welcome,
                    )
                )
                try {
                    panel.setShown(true)
                    test(panel, UIUtil.findComponentOfType(panel, Tree::class.java)!!)
                } finally {
                    Disposer.dispose(panel)
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
