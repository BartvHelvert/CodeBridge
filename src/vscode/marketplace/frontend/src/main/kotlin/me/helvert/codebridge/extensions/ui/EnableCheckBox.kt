// Ported from com.intellij.ide.plugins.newui.ListPluginComponent.createEnableDisableButton in
// intellij-community. Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source
// code is governed by the Apache 2.0 license. See NOTICE.md in the project root.
//
// Changes from the original: a named class taking the initial state and a callback with the new
// one, instead of an anonymous subclass wired to the plugin model.
package me.helvert.codebridge.extensions.ui

import com.intellij.ui.components.OnOffButton

/** Native enable switch, centered vertically by the row layout. */
class EnableCheckBox(enabled: Boolean, onChanged: (Boolean) -> Unit) : OnOffButton() {

    init {
        isSelected = enabled
        isOpaque = false
        getAccessibleContext().accessibleName = "Enabled"
        addActionListener { onChanged(isSelected) }
    }

}
