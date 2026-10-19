// Ported from com.intellij.ide.plugins.newui.ListPluginComponent.BaselineLayout in
// intellij-community. Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source
// code is governed by the Apache 2.0 license. See NOTICE.md in the project root.
//
// Changes from the original: extracted from the inner class of ListPluginComponent into a
// standalone row component, with the plugin model, checkbox, progress indicator and OnOffButton
// handling removed. Rows use compact padding and center action controls beside the full text block.
package me.helvert.codebridge.extensions.ui

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.AbstractLayoutManager
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBValue
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min

/**
 * One row of the extension list, laid out exactly as the IDE lays out a plugin row.
 *
 * The behaviour worth keeping is all in [RowLayout]: the name is truncated to whatever space the
 * buttons leave (and grows a tooltip when it is), buttons are right-aligned and vertically centered,
 * and the line components below get the full remaining width so they ellipsize.
 */
open class PluginRow : JPanel() {

    private val rowLayout = RowLayout()

    init {
        layout = rowLayout
        border = JBUI.Borders.empty(6, 16)
    }

    fun setIconComponent(component: JComponent) {
        rowLayout.iconComponent = component
        add(component)
    }

    fun setNameComponent(component: JLabel) {
        rowLayout.nameComponent = component
        add(component)
    }

    fun setTagComponent(component: JComponent) {
        rowLayout.tagComponent = component
        add(component)
    }

    fun addLineComponent(component: JComponent) {
        rowLayout.lineComponents.add(component)
        add(component)
    }

    fun addButtonComponent(component: JComponent) {
        rowLayout.buttonComponents.add(component)
        add(component)
    }

    private inner class RowLayout : AbstractLayoutManager() {
        private val hGap: JBValue = JBValue.Float(12f)
        private val hOffset: JBValue = JBValue.Float(8f)
        private val buttonOffset: JBValue = JBValue.Float(6f)

        var iconComponent: JComponent? = null
        var nameComponent: JLabel? = null
        var tagComponent: JComponent? = null
        val buttonComponents: MutableList<JComponent> = ArrayList()
        val lineComponents: MutableList<JComponent> = ArrayList()

        override fun preferredLayoutSize(parent: Container): Dimension {
            val name = nameComponent ?: return Dimension(0, 0)
            val result = Dimension(name.preferredSize)

            tagComponent?.let {
                val size = it.preferredSize
                result.width += size.width + 2 * hOffset.get()
                result.height = max(result.height, size.height)
            }

            var visibleCount = 0
            var buttonsHeight = 0
            for (component in buttonComponents) {
                if (component.isVisible) {
                    val size = component.preferredSize
                    result.width += size.width
                    buttonsHeight = max(buttonsHeight, size.height)
                    visibleCount++
                }
            }
            if (visibleCount > 0) {
                result.width += hOffset.get()
                result.width += (visibleCount - 1) * buttonOffset.get()
            }

            for (component in lineComponents) {
                if (component.isVisible) {
                    val size = component.preferredSize
                    result.width = max(result.width, size.width)
                    result.height += size.height
                }
            }

            // Buttons sit beside the complete text block, so their height must not be
            // added to the metadata line as though they belonged to the title line.
            result.height = max(result.height + JBUIScale.scale(2), buttonsHeight)

            iconComponent?.let {
                val iconSize = it.preferredSize
                result.width += iconSize.width + hGap.get()
                result.height = max(result.height, iconSize.height)
            }

            JBInsets.addTo(result, parent.insets)
            return result
        }

        override fun layoutContainer(parent: Container) {
            val name = nameComponent ?: return
            val icon = iconComponent ?: return
            val insets = parent.insets
            var x = insets.left
            var y = insets.top

            val iconSize = icon.preferredSize
            icon.setBounds(x, (parent.height - iconSize.height) / 2, iconSize.width, iconSize.height)
            x += iconSize.width + hGap.get()
            y += JBUIScale.scale(2)

            val width20 = JBUIScale.scale(20)
            val calcNameWidth = max(width20, calculateNameWidth(parent))
            val nameSize = name.preferredSize
            val baseline = y + name.getBaseline(nameSize.width, nameSize.height)

            name.toolTipText = if (calcNameWidth < nameSize.width) name.text else null
            nameSize.width = min(nameSize.width, calcNameWidth)
            name.setBounds(x, y, nameSize.width, nameSize.height)
            y += nameSize.height

            val width = parent.width
            var nextX = x + nameSize.width + hOffset.get()

            tagComponent?.let {
                val size = it.preferredSize
                setBaselineBounds(nextX, baseline, it, size, insets.top)
                nextX += size.width
            }

            var lastX = width - insets.right
            if (calcNameWidth > width20) {
                for (component in buttonComponents.asReversed()) {
                    if (!component.isVisible) continue
                    val size = component.preferredSize
                    lastX -= size.width
                    component.setBounds(lastX, (parent.height - size.height) / 2, size.width, size.height)
                    lastX -= buttonOffset.get()
                }
            } else {
                for (component in buttonComponents) {
                    if (component.isVisible) {
                        val size = component.preferredSize
                        setBaselineBounds(nextX, baseline, component, size, insets.top)
                        nextX += size.width + buttonOffset.get()
                    }
                }
            }

            val lineWidth = width - x - insets.right
            for (component in lineComponents) {
                if (component.isVisible) {
                    val lineHeight = component.preferredSize.height
                    component.setBounds(x, y, lineWidth, lineHeight)
                    y += lineHeight
                }
            }
        }

        private fun calculateNameWidth(parent: Container): Int {
            val insets = parent.insets
            var width = parent.width - insets.left - insets.right -
                (iconComponent?.preferredSize?.width ?: 0) - hGap.get()

            tagComponent?.let { width -= it.preferredSize.width + 2 * hOffset.get() }

            var visibleCount = 0
            for (component in buttonComponents) {
                if (component.isVisible) {
                    width -= component.preferredSize.width
                    visibleCount++
                }
            }
            width -= buttonOffset.get() * (visibleCount - 1)
            if (visibleCount > 0) width -= hOffset.get()

            return width
        }

        private fun setBaselineBounds(x: Int, y: Int, component: Component, size: Dimension, top: Int) {
            if (component is ActionToolbar) {
                component.setBounds(x, top - JBUI.scale(1), size.width, size.height)
            } else {
                component.setBounds(x, y - component.getBaseline(size.width, size.height), size.width, size.height)
            }
        }
    }
}
