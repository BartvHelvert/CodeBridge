package me.helvert.codebridge.views

import me.helvert.codebridge.rpc.TreeViewDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerBadgeCountsTest {
    private fun view(id: String, container: String, badge: Int?) =
        TreeViewDto(id, id, "extension", container, container, badge = badge)

    @Test
    fun `GitLens view badges add up on its icon and stay separate from other containers`() {
        val views = listOf(
            view("launchpad", "gitlens", 2),
            view("repositories", "gitlens", null),
            view("inspect", "gitlensInspect", 5),
        )
        assertEquals(mapOf("gitlens" to 2, "gitlensInspect" to 5), containerBadgeCounts(views))
        assertEquals(5, containerBadgeCounts(views + view("worktrees", "gitlens", 3))["gitlens"])
        assertEquals(0, containerBadgeCounts(views.map { it.copy(badge = null) })["gitlens"])
        assertEquals(emptyMap(), containerBadgeCounts(emptyList()))
    }

    @Test
    fun `hiding a view does not discard its activity badge`() {
        assertEquals(
            2, containerBadgeCounts(
                listOf(
                    view("launchpad", "gitlens", 2).copy(hiddenByDefault = true, visible = false),
                    view("repositories", "gitlens", -1),
                )
            )["gitlens"]
        )
    }
}
