// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copied from com.intellij.ide.plugins.newui.TagComponent in intellij-community. See NOTICE.md in the project root.
//
// Changes from the original: the EAP/paid/trial/ultimate tag styling is removed.
package me.helvert.codebridge.extensions.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * @author Alexander Lobas
 */
public final class TagComponent extends LinkComponent {
  private static final Color BACKGROUND = JBColor.namedColor("Plugins.tagBackground", new JBColor(0xEAEAEC, 0x4D4D4D));
  private static final Color FOREGROUND = JBColor.namedColor("Plugins.tagForeground", new JBColor(0x787878, 0x999999));


  private final Color myColor = BACKGROUND;

  public TagComponent() {
    setForeground(FOREGROUND);
    setPaintUnderline(false);
    setOpaque(false);
    setBorder(JBUI.Borders.empty(1, 8));
  }

  public TagComponent(@NotNull @Nls String name) {
    this();
    setText(name);
  }

  @Override
  protected void paintComponent(Graphics g) {
    //noinspection UseJBColor
    g.setColor(myUnderline ? new Color(myColor.getRed(), myColor.getGreen(), myColor.getBlue(), 178) : myColor);
    g.fillRect(0, 0, getWidth(), getHeight());
    super.paintComponent(g);
  }

  @Override
  protected boolean isInClickableArea(Point pt) {
    return true;
  }
}