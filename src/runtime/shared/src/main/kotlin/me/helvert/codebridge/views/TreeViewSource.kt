package me.helvert.codebridge.views

import me.helvert.codebridge.rpc.TreeActionDto
import me.helvert.codebridge.rpc.TreeChildrenDto

/**
 * Where a [CodeBridgeTreePanel] gets its items, and where it reports what the user does with them.
 *
 * Over RPC for a panel in a frontend tool window; straight from the backend's tree views for a
 * panel the backend shows itself, as a tab of a debug session.
 */
interface TreeViewSource {
    suspend fun children(viewId: String, handle: String?): TreeChildrenDto
    suspend fun visible(viewId: String, visible: Boolean)
    suspend fun expanded(viewId: String, handle: String, expanded: Boolean)
    suspend fun selection(viewId: String, handles: List<String>, focus: String?)
    suspend fun run(viewId: String, handle: String)
    suspend fun icon(key: String): ByteArray?
    suspend fun itemActions(viewId: String, handle: String): List<TreeActionDto>
    suspend fun runAction(viewId: String, actionId: Int, handle: String?, selection: List<String>)
    suspend fun runWelcomeLink(viewId: String, link: Int)
    suspend fun checkboxes(viewId: String, states: Map<String, Boolean>)
}
