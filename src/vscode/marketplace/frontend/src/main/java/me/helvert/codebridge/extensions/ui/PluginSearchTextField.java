// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
// Copied from com.intellij.ide.plugins.newui.PluginSearchTextField in intellij-community. See NOTICE.md in the project root.
//
// Changes from the original: the package declaration only.
package me.helvert.codebridge.extensions.ui;

import com.intellij.ui.SearchTextField;

import java.util.Collections;

/**
 * @author Alexander Lobas
 */
public class PluginSearchTextField extends SearchTextField {
  @Override
  public void setHistoryPropertyName(String historyPropertyName) {
    super.setHistoryPropertyName(historyPropertyName);
  }

  @Override
  protected void setEmptyHistory() {
    setHistory(Collections.emptyList());
  }

  @Override
  public void setSelectedItem(String s) {
  }
}