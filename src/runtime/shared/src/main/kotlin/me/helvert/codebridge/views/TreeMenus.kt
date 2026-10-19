package me.helvert.codebridge.views

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAwareAction
import me.helvert.codebridge.rpc.TreeActionDto
import javax.swing.Icon

/** An extension's menu for a view or its items, as IntelliJ actions. */
object TreeMenus {

    /**
     * [actions] in menu order, with a separator between groups and a submenu for an entry with
     * children. [icon] is asked on every update, so an image fetched after the menu is made still
     * shows; [run] runs an entry.
     */
    fun grouped(
        actions: List<TreeActionDto>,
        icon: (TreeActionDto) -> Icon?,
        run: (TreeActionDto) -> Unit
    ): List<AnAction> {
        val result = mutableListOf<AnAction>()
        var group: String? = null
        for (entry in actions) {
            if (group != null && entry.group != group) result += Separator.getInstance()
            group = entry.group
            result += action(entry, icon, run)
        }
        return result
    }

    fun action(entry: TreeActionDto, icon: (TreeActionDto) -> Icon?, run: (TreeActionDto) -> Unit): AnAction =
        if (entry.children.isNotEmpty()) DefaultActionGroup(entry.title, grouped(entry.children, icon, run)).apply {
            isPopup = true
            templatePresentation.icon = icon(entry)
        }
        else object : DumbAwareAction(entry.title) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = entry.enabled
                event.presentation.icon = icon(entry)
            }

            override fun actionPerformed(event: AnActionEvent) = run(entry)
        }
}
