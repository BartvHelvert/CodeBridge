package me.helvert.codebridge.launch

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The runs of child debug sessions, by the id of the session that started them.
 *
 * A child attaches to something its parent's adapter owns -- js-debug's worker or subprocess --
 * and is meaningless once the parent is gone, so VS Code stops children with their parent. A
 * child's own children follow the same way, as each child stopping ends its session in turn.
 */
internal object CodeBridgeChildSessions {
    private val children = ConcurrentHashMap<String, MutableSet<ProcessHandler>>()

    fun add(parent: String, handler: ProcessHandler) {
        children.computeIfAbsent(parent) { CopyOnWriteArraySet() }.add(handler)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) { children[parent]?.remove(handler) }
        })
        // It may have ended before anyone listened.
        if (handler.isProcessTerminated) children[parent]?.remove(handler)
    }

    /** Stops what [parent] started, once it has ended. */
    fun stop(parent: String) {
        children.remove(parent)?.forEach { if (!it.isProcessTerminated && !it.isProcessTerminating) it.destroyProcess() }
    }
}
