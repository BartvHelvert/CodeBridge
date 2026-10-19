// Ported from com.intellij.ide.plugins.newui.buttons.OptionButton in intellij-community.
// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license. See NOTICE.md in the project root.
//
// Changes from the original: the action and options are set after construction only.
package me.helvert.codebridge.extensions.ui

import com.intellij.ui.components.JBOptionButton
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JButton

/** The split Enable/Disable button of the IDE's plugin details page, with its options in the arrow. */
class OptionButton : JBOptionButton(null, null) {

    private val baseline = JButton()

    init {
        addSeparator = false
        selectFirstItem = false
        popupBackgroundColor = UIUtil.getListBackground()
        showPopupYOffset = -2
        // The popup is at least as wide as the button.
        popupHandler = {
            val size = Dimension(it.size)
            val width = width - insets.left - insets.right
            if (size.width <= width) size.width = width
            it.size = size
        }
    }

    override fun getBaseline(width: Int, height: Int): Int {
        baseline.text = text
        baseline.size = size
        return baseline.getBaseline(width, height)
    }

    override fun setEnabled(b: Boolean) {
        super.setEnabled(b)
        action?.isEnabled = b
    }
}
