// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copied from com.intellij.ide.plugins.newui.BaselinePanel in intellij-community. See NOTICE.md in the project root.
//
// Changes from the original: setListeners and setProgressComponent are removed.
package me.helvert.codebridge.extensions.ui;

import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.ui.AbstractLayoutManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.JBValue;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class BaselinePanel extends NonOpaquePanel {
  private Component myBaseComponent;
  private final List<Component> myButtonComponents = new ArrayList<>();

  private final JBValue myOffset = new JBValue.Float(8);
  private final JBValue myBeforeButtonOffset;
  private final JBValue myButtonOffset = new JBValue.Float(6);


  public BaselinePanel() {
    this(40, true);
  }

  public BaselinePanel(int beforeButtonOffset, boolean leftOrder) {
    myBeforeButtonOffset = new JBValue.Float(beforeButtonOffset);

    setBorder(JBUI.Borders.empty(5, 0, 6, 0));

    setLayout(new AbstractLayoutManager() {
      @Override
      public Dimension preferredLayoutSize(Container parent) {
        Dimension baseSize = myBaseComponent.getPreferredSize();
        int width = baseSize.width;

        int visibleCount = 0;
        for (Component component : myButtonComponents) {
          if (component.isVisible()) {
            width += component.getPreferredSize().width;
            visibleCount++;
          }
        }

        if (visibleCount > 0) {
          width += myBeforeButtonOffset.get();
          width += (visibleCount - 1) * myButtonOffset.get();
        }

        Insets insets = parent.getInsets();
        return new Dimension(width, Math.max(insets.top + baseSize.height + insets.bottom, getMinButtonsHeight()));
      }

      private int calculateBaseWidth(@NotNull Container parent) {
        int parentWidth = parent.getWidth();

        int visibleCount = 0;
        for (Component component : myButtonComponents) {
          if (component.isVisible()) {
            parentWidth -= component.getPreferredSize().width;
            visibleCount++;
          }
        }
        parentWidth -= myButtonOffset.get() * (visibleCount - 1);
        if (visibleCount > 0) {
          parentWidth -= myOffset.get();
        }

        return parentWidth;
      }

      @Override
      public void layoutContainer(Container parent) {
        Dimension baseSize = myBaseComponent.getPreferredSize();
        int baseComponentBaseline = myBaseComponent.getBaseline(baseSize.width, baseSize.height);
        int top = parent.getInsets().top;
        int y = Math.max(top + baseComponentBaseline, getMinButtonsBaseline());

        if (!leftOrder) {
          layoutRightOrderContainer(parent, y, baseComponentBaseline);
          return;
        }

        int x = 0;
        int calcBaseWidth = calculateBaseWidth(parent);

        if (myBaseComponent instanceof JLabel label) {
          label.setToolTipText(calcBaseWidth < baseSize.width ? label.getText() : null);
        }

        baseSize.width = Math.min(baseSize.width, calcBaseWidth);
        myBaseComponent.setBounds(x, y - baseComponentBaseline, baseSize.width, baseSize.height);

        int lastX = parent.getWidth();

        for (Component component : myButtonComponents.reversed()) {
          if (!component.isVisible()) {
            continue;
          }
          Dimension size = component.getPreferredSize();
          lastX -= size.width;
          setBaselineBounds(lastX, y, component, size);
          lastX -= myButtonOffset.get();
        }
      }

      private int getMinButtonsHeight() {
        return myButtonComponents.stream()
          .filter(Component::isVisible)
          .mapToInt(component -> component.getPreferredSize().height)
          .max().orElse(0);
      }

      private int getMinButtonsBaseline() {
        return myButtonComponents.stream()
          .filter(Component::isVisible)
          .mapToInt(component -> component.getBaseline(Math.max(0, getWidth()), Math.max(0, getHeight())))
          .max().orElse(-1);
      }

      private void layoutRightOrderContainer(Container parent, int y, int baseComponentBaseline) {
        int x = 0;
        boolean buttons = false;

        for (Component component : myButtonComponents) {
          if (!component.isVisible()) {
            continue;
          }

          Dimension size = component.getPreferredSize();
          setBaselineBounds(x, y, component, size);
          x += size.width + myButtonOffset.get();
          buttons = true;
        }

        int width = parent.getWidth();
        if (buttons) {
          x -= myButtonOffset.get();
          x += myBeforeButtonOffset.get();
          width -= x;
        }

        Dimension baseSize = myBaseComponent.getPreferredSize();
        myBaseComponent.setBounds(x, y - baseComponentBaseline, width, baseSize.height);

        if (myBaseComponent instanceof JLabel label) {
          label.setToolTipText(width < baseSize.width ? label.getText() : null);
        }
      }

      private void setBaselineBounds(int x, int y, @NotNull Component component, @NotNull Dimension size) {
        if (component instanceof ActionToolbar) {
          component.setBounds(x, getInsets().top - JBUI.scale(1), size.width, size.height);
        }
        else {
          component.setBounds(x, y - component.getBaseline(size.width, size.height), size.width, size.height);
        }
      }
    });
  }

  @Override
  public Component add(Component component) {
    assert myBaseComponent == null;
    myBaseComponent = component;
    return super.add(component);
  }

  public void addButtonComponent(@NotNull JComponent component) {
    myButtonComponents.add(component);
    add(component, null);
  }
}
