// Ported from com.intellij.ide.plugins.newui.TextHorizontalLayout in intellij-community.
// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license. See NOTICE.md in the project root.
//
// Changes from the original: Kotlin, and built on the platform's public ListLayout instead of
// extending the plugin page's own HorizontalLayout, which is deprecated. The gap is unscaled,
// as ListLayout scales it.
package me.helvert.codebridge.extensions.ui

import com.intellij.ui.components.panels.ListLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager2
import javax.swing.JLabel

/**
 * Lays labels out in a row, like the metrics line under a plugin's name: if the row is too narrow,
 * the label added with [FIX_LABEL] is cut to what is left and shows its full text as a tooltip.
 */
class TextHorizontalLayout(gap: Int) : LayoutManager2 {

    private val row = ListLayout.horizontal(gap)
    private var fixLabel: JLabel? = null

    override fun addLayoutComponent(component: Component, constraints: Any?) {
        if (constraints == FIX_LABEL) fixLabel = component as JLabel
        // ListLayout rejects constraints it does not know, and this one is ours.
        row.addLayoutComponent(component, null)
    }

    override fun addLayoutComponent(name: String?, component: Component) = row.addLayoutComponent(name, component)

    override fun removeLayoutComponent(component: Component) {
        if (component === fixLabel) fixLabel = null
        row.removeLayoutComponent(component)
    }

    override fun preferredLayoutSize(parent: Container): Dimension = row.preferredLayoutSize(parent)
    override fun minimumLayoutSize(parent: Container): Dimension = row.minimumLayoutSize(parent)
    override fun maximumLayoutSize(parent: Container): Dimension = row.maximumLayoutSize(parent)
    override fun getLayoutAlignmentX(parent: Container): Float = row.getLayoutAlignmentX(parent)
    override fun getLayoutAlignmentY(parent: Container): Float = row.getLayoutAlignmentY(parent)
    override fun invalidateLayout(parent: Container) = row.invalidateLayout(parent)

    override fun layoutContainer(parent: Container) {
        row.layoutContainer(parent)
        val label = fixLabel ?: return
        if (parent.width < label.x + label.width) {
            label.toolTipText = label.text
            label.setSize(parent.width - label.x, label.height)
        } else {
            label.toolTipText = null
        }
    }

    companion object {
        /** The constraint for the one label that gives way when the row runs out of room. */
        const val FIX_LABEL: String = "fix_label"
    }
}
