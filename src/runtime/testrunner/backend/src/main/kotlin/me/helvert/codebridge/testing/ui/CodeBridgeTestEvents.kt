package me.helvert.codebridge.testing.ui

import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.testing.HostedTesting

/**
 * A run's events, as the IDE's test runner reads them: TeamCity service messages, one per line, in
 * the id-based form (`nodeId`, `parentNodeId`), which lets a test be reported under any suite at any
 * time rather than only under the one that started last.
 *
 * VS Code has no tests and suites, only items, any of which may have a state and children. An item
 * is a suite here if its controller says it has children, or once the run reports one under it.
 * Each controller's own root item is left out: it is the extension, not a test.
 *
 * A suite stays open until its children have finished and it has a final state of its own, or until
 * the run ends; a suite that failed -- a file that did not compile -- fails when it closes, since a
 * failure ends a node in the test runner and its children would be lost.
 *
 * [hasChildren] asks the controller's tree; [send] receives a service message, [print] why the run
 * ended, once every test has.
 */
class CodeBridgeTestEvents(
    private val hasChildren: (String) -> Boolean,
    private val send: (String) -> Unit,
    private val print: (String) -> Unit,
) {
    private class Node(val nodeId: String, val parentId: String?, val suite: Boolean) {
        var open = true
        var finalState: Testing.State? = null
        var messages: List<Testing.Message> = emptyList()
        val children = mutableListOf<String>()
    }

    private val items = HashMap<String, Testing.Item>()
    private val nodes = LinkedHashMap<String, Node>()
    private var nextId = 0

    fun started() {
        send(ServiceMessageBuilder("enteredTheMatrix").toString())
        send(ServiceMessageBuilder.testsStarted().toString())
    }

    fun accept(event: HostedTesting.Run.Event) {
        when (event) {
            is HostedTesting.Run.Event.TaskStarted -> Unit
            is HostedTesting.Run.Event.Added -> event.items.forEach { item ->
                items[item.extId] = item
                parentOf(item.extId)?.let { ensureSuite(it) }
            }

            is HostedTesting.Run.Event.State -> state(event)
            is HostedTesting.Run.Event.Output -> output(event.text, event.testId)
            is HostedTesting.Run.Event.Ended -> ended(event.problem)
        }
    }

    private fun state(event: HostedTesting.Run.Event.State) {
        val existing = nodes[event.testId]
        if (existing != null && !existing.open) return
        val suite = existing?.suite ?: hasChildren(event.testId)
        when (event.state) {
            Testing.State.UNSET, Testing.State.QUEUED -> Unit
            Testing.State.RUNNING -> if (suite) ensureSuite(event.testId) else ensureTest(event.testId)
            else -> if (suite) {
                val node = ensureSuite(event.testId) ?: return
                node.finalState = event.state
                node.messages = event.messages
                closeIfDone(event.testId)
            } else {
                val node = ensureTest(event.testId) ?: return
                finishTest(node, event.state, event.messages, event.duration)
            }
        }
    }

    /**
     * Output for a test goes to it; the rest goes to the run itself. Printed as plain text it would
     * land on whichever test the test runner thinks is running, and tests run side by side -- a
     * runner that streams its whole log without saying whose it is, as rust-analyzer does, would
     * put one test's panic under another.
     */
    private fun output(text: String, testId: String?) {
        val node = testId?.let { nodes[it] }?.takeIf { it.open }
        send(
            ServiceMessageBuilder.testStdOut(testId?.let(::label).orEmpty())
                .addAttribute("nodeId", node?.nodeId ?: ROOT)
                .addAttribute("out", withoutAnsi(text).replace("\r\n", "\n")).toString()
        )
    }

    private fun ended(problem: String?) {
        // Deepest first, so a suite closes after everything under it.
        for ((extId, node) in nodes.entries.reversed()) {
            if (!node.open) continue
            if (node.suite) close(extId, node)
            else finishTest(node, Testing.State.SKIPPED, emptyList(), null, "The run ended before this test finished")
        }
        problem?.let { print(if (it.endsWith("\n")) it else "$it\n") }
        send(ServiceMessageBuilder("testingFinished").toString())
    }

    /** The item's parent, unless that is the controller's root. */
    private fun parentOf(extId: String): String? =
        Testing.TestId.parent(extId)?.takeUnless(Testing.TestId::isRoot)

    /** The nearest open suite above [extId]: a test cannot have children in the test runner. */
    private fun parentNode(extId: String): String {
        var parent = parentOf(extId)
        while (parent != null) {
            val node = nodes[parent] ?: ensureSuite(parent)
            if (node != null && node.suite && node.open) return node.nodeId
            parent = parentOf(parent)
        }
        return ROOT
    }

    private fun ensureSuite(extId: String): Node? {
        nodes[extId]?.let { return it }
        if (Testing.TestId.isRoot(extId)) return null
        val parent = parentNode(extId)
        val node = Node((++nextId).toString(), parent, suite = true)
        nodes[extId] = node
        nodeFor(parent)?.children?.add(extId)
        send(started(ServiceMessageBuilder.testSuiteStarted(label(extId)), extId, node))
        return node
    }

    private fun ensureTest(extId: String): Node? {
        nodes[extId]?.let { return it }
        if (Testing.TestId.isRoot(extId)) return null
        val parent = parentNode(extId)
        val node = Node((++nextId).toString(), parent, suite = false)
        nodes[extId] = node
        nodeFor(parent)?.children?.add(extId)
        send(started(ServiceMessageBuilder.testStarted(label(extId)), extId, node))
        return node
    }

    private fun started(message: ServiceMessageBuilder, extId: String, node: Node): String {
        message.addAttribute("nodeId", node.nodeId).addAttribute("parentNodeId", node.parentId ?: ROOT)
        items[extId]?.let(::locationHint)?.let { message.addAttribute("locationHint", it) }
        return message.toString()
    }

    private fun finishTest(
        node: Node,
        state: Testing.State,
        messages: List<Testing.Message>,
        duration: Double?,
        comment: String? = null
    ) {
        val extId = nodes.entries.first { it.value === node }.key
        val name = label(extId)
        when (state) {
            Testing.State.FAILED, Testing.State.ERRORED -> send(
                failure(
                    name,
                    node,
                    messages,
                    state == Testing.State.ERRORED
                )
            )

            Testing.State.SKIPPED -> send(
                ServiceMessageBuilder.testIgnored(name).addAttribute("nodeId", node.nodeId)
                .apply { comment?.let { addAttribute("message", it) } }.toString()
            )

            else -> Unit
        }
        if (state != Testing.State.FAILED && state != Testing.State.ERRORED) {
            send(
                ServiceMessageBuilder.testFinished(name).addAttribute("nodeId", node.nodeId)
                .apply { duration?.let { addAttribute("duration", it.toLong().toString()) } }.toString()
            )
        }
        node.open = false
        node.parentId?.let(::extIdOf)?.let(::closeIfDone)
    }

    private fun closeIfDone(extId: String) {
        val node = nodes[extId] ?: return
        if (!node.open || node.finalState == null) return
        if (node.children.any { nodes[it]?.open == true }) return
        close(extId, node)
    }

    private fun close(extId: String, node: Node) {
        val failed = node.finalState == Testing.State.FAILED || node.finalState == Testing.State.ERRORED
        if (failed) send(failure(label(extId), node, node.messages, node.finalState == Testing.State.ERRORED))
        else send(ServiceMessageBuilder.testSuiteFinished(label(extId)).addAttribute("nodeId", node.nodeId).toString())
        node.open = false
        node.parentId?.let(::extIdOf)?.let(::closeIfDone)
    }

    /**
     * `testFailed`, with the first message as what failed and the rest -- with every stack -- as the
     * details. A message with both `expected` and `actual` is a comparison, which the test runner
     * offers to show as a diff.
     */
    private fun failure(name: String, node: Node, messages: List<Testing.Message>, error: Boolean): String {
        val message = ServiceMessageBuilder.testFailed(name).addAttribute("nodeId", node.nodeId)
            .addAttribute("message", messages.firstOrNull()?.text?.trim().orEmpty())
            .addAttribute("details", details(messages))
        messages.firstOrNull { it.expected != null && it.actual != null }?.let {
            message.addAttribute("type", "comparisonFailure").addAttribute("expected", it.expected!!)
                .addAttribute("actual", it.actual!!)
        }
        if (error) message.addAttribute("error", "true")
        return message.toString()
    }

    private fun details(messages: List<Testing.Message>): String = buildString {
        messages.forEachIndexed { index, message ->
            if (index > 0) appendLine(message.text.trim())
            message.uri?.let { uri ->
                appendLine(
                    "\tat ${
                        place(
                            uri,
                            message.range?.startLineNumber,
                            message.range?.startColumn
                        )
                    }"
                )
            }
            for ((label, frameUri, position) in message.stackTrace) {
                val at = frameUri?.let { place(it, position?.lineNumber, position?.column) }
                appendLine(if (at != null) "\tat $label ($at)" else "\tat $label")
            }
        }
    }.trimEnd()

    /** `path:line:column`, which the console turns into a link. */
    private fun place(uri: UriComponents, line: Int?, column: Int?): String {
        val path = if (uri.scheme == UriComponents.FILE) runCatching {
            uri.toPath().toString()
        }.getOrDefault(uri.path) else uri.uri
        return listOfNotNull(path, line?.toString(), line?.let { column?.toString() }).joinToString(":")
    }

    private fun nodeFor(nodeId: String): Node? = nodes.values.firstOrNull { it.nodeId == nodeId }

    private fun extIdOf(nodeId: String): String? = nodes.entries.firstOrNull { it.value.nodeId == nodeId }?.key

    private fun label(extId: String): String =
        items[extId]?.label?.let(::withoutIcons)?.takeIf { it.isNotBlank() } ?: Testing.TestId.localId(extId)

    companion object {
        private const val ROOT = "0"
        private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val ICON = Regex("\\$\\([a-z0-9-~]+\\)\\s*")

        fun withoutAnsi(text: String): String = text.replace(ANSI, "")

        /** `$(beaker)` and the like, which VS Code draws as codicons. */
        fun withoutIcons(label: String): String = label.replace(ICON, "").trim()

        /** Where the test runner navigates from a test; see [CodeBridgeTestLocator]. */
        fun locationHint(item: Testing.Item): String? {
            val uri = item.uri?.takeIf { it.scheme == UriComponents.FILE } ?: return null
            return CodeBridgeTestLocator.hint(item.extId, uri.uri, item.range?.startLineNumber, item.range?.startColumn)
        }
    }
}
