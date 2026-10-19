// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
// Copied from com.intellij.ide.plugins.newui.LinkComponent in intellij-community. See NOTICE.md in the project root.
//
// Changes from the original: the package declaration only.
package me.helvert.codebridge.extensions.ui;

import com.intellij.ui.components.labels.LinkLabel;

import java.awt.*;

/**
 * @author Alexander Lobas
 */
public class LinkComponent extends LinkLabel<Object> {
  public LinkComponent() {
    super("", null);
  }

  @Override
  protected Color getTextColor() {
    return getForeground();
  }
}