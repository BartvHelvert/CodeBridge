// Modelled on com.intellij.ide.plugins.newui.PluginListLayout in intellij-community.
// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license. See NOTICE.md in the project root.
package me.helvert.codebridge.extensions.ui

import com.intellij.util.ui.AbstractLayoutManager
import java.awt.Container
import java.awt.Dimension

/**
 * Stacks rows at the full width of the container.
 *
 * The important part is that [preferredLayoutSize] reports a width of **zero**, exactly as
 * `PluginListLayout` does. A row's preferred width is the width of its untruncated description,
 * which is unbounded; reporting that propagates out through the splitter and makes the whole
 * settings dialog wider than the window, which is what a horizontal scrollbar across the page
 * really means. Rows are given whatever width the viewport has and truncate themselves.
 */
class RowStackLayout : AbstractLayoutManager() {

    override fun preferredLayoutSize(parent: Container): Dimension {
        var height = 0
        for (component in parent.components) {
            if (component.isVisible) height += component.preferredSize.height
        }
        return Dimension(0, height)
    }

    override fun layoutContainer(parent: Container) {
        val width = parent.width
        var y = 0
        for (component in parent.components) {
            if (!component.isVisible) continue
            val height = component.preferredSize.height
            component.setBounds(0, y, width, height)
            y += height
        }
    }
}
