/*
 * This file is part of Arduino 1.8 Remastered.
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cc.arduino.remastered.autocomplete;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import processing.app.Theme;
import processing.app.syntax.SketchTextArea;

/**
 * The completion list, drawn like the editor itself: editor font and
 * background, every entry colored exactly as the syntax highlighting would
 * color that word, and the current-line highlight color for the selection.
 */
final class CompletionPopup {

  private static final int VISIBLE_ROWS = 10;

  private final SketchTextArea textArea;
  private final Consumer<Completion> onAccept;
  private final JList<Completion> list = new JList<>();
  private final JScrollPane scroll = new JScrollPane(list);
  private JWindow window;

  CompletionPopup(SketchTextArea textArea, Consumer<Completion> onAccept) {
    this.textArea = textArea;
    this.onAccept = onAccept;
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setFocusable(false);
    list.setCellRenderer(new Renderer());
    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
          onAccept.accept(list.getSelectedValue());
        }
      }
    });
    scroll.setFocusable(false);
    scroll.getVerticalScrollBar().setFocusable(false);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setBorder(BorderFactory.createLineBorder(new Color(0xcc, 0xcc, 0xcc)));
    scroll.setViewportBorder(null);
  }

  boolean isVisible() {
    return window != null && window.isVisible();
  }

  Completion getSelected() {
    return list.getSelectedValue();
  }

  /**
   * Shows the list below (or above) the given document offset, with the
   * entries' text aligned under the start of the word being completed.
   */
  void show(List<Completion> items, int wordStartOffset) {
    Window owner = SwingUtilities.getWindowAncestor(textArea);
    if (owner == null || !textArea.isShowing()) {
      return;
    }
    if (window == null || window.getOwner() != owner) {
      if (window != null) window.dispose();
      window = new JWindow(owner);
      window.setFocusableWindowState(false);
      window.getContentPane().setLayout(new BorderLayout());
      window.getContentPane().add(scroll, BorderLayout.CENTER);
    }

    Font font = textArea.getFont();
    list.setFont(font);
    list.setBackground(textArea.getBackground());
    list.setListData(items.toArray(new Completion[0]));
    list.setSelectedIndex(0);
    list.ensureIndexIsVisible(0);

    FontMetrics fm = textArea.getFontMetrics(font);
    int rowHeight = fm.getHeight() + 4;
    list.setFixedCellHeight(rowHeight);
    int widest = 0;
    for (Completion c : items) {
      widest = Math.max(widest, fm.stringWidth(c.text) + fm.stringWidth("  ") + labelWidth(c, fm));
    }
    int rows = Math.min(VISIBLE_ROWS, Math.max(1, items.size()));
    int width = Math.max(180, Math.min(520, widest + Renderer.PAD * 2 + 6))
      + (items.size() > VISIBLE_ROWS ? scroll.getVerticalScrollBar().getPreferredSize().width : 0);
    int height = rows * rowHeight + 2;
    window.setSize(new Dimension(width, height));

    Rectangle caret;
    try {
      caret = textArea.modelToView(wordStartOffset);
    } catch (Exception e) {
      return;
    }
    if (caret == null) {
      return;
    }
    Point p = new Point(caret.x - Renderer.PAD - 1, caret.y + caret.height);
    SwingUtilities.convertPointToScreen(p, textArea);
    GraphicsConfiguration gc = textArea.getGraphicsConfiguration();
    if (gc != null) {
      Rectangle screen = gc.getBounds();
      if (p.y + height > screen.y + screen.height) {
        Point top = new Point(caret.x, caret.y);
        SwingUtilities.convertPointToScreen(top, textArea);
        p.y = top.y - height; // not enough room below the line: open upwards
      }
      p.x = Math.max(screen.x, Math.min(p.x, screen.x + screen.width - width));
    }
    window.setLocation(p);
    if (!window.isVisible()) {
      window.setVisible(true);
    }
    window.repaint();
  }

  void hide() {
    if (window != null) {
      window.setVisible(false);
    }
  }

  void dispose() {
    if (window != null) {
      window.dispose();
      window = null;
    }
  }

  void move(int delta) {
    int n = list.getModel().getSize();
    if (n == 0) return;
    int i = list.getSelectedIndex() + delta;
    if (Math.abs(delta) == 1) {
      i = (i + n) % n; // single steps wrap around
    } else {
      i = Math.max(0, Math.min(n - 1, i));
    }
    list.setSelectedIndex(i);
    list.ensureIndexIsVisible(i);
  }

  int pageSize() {
    return VISIBLE_ROWS - 1;
  }

  private static int labelWidth(Completion c, FontMetrics fm) {
    return c.source.isEmpty() ? 0 : fm.stringWidth(c.source) * 85 / 100 + 16;
  }

  /** Paints one row with the editor's own colors. */
  private final class Renderer extends JComponent implements ListCellRenderer<Completion> {
    static final int PAD = 6;

    private Completion value;
    private boolean selected;

    @Override
    public Component getListCellRendererComponent(JList<? extends Completion> l, Completion value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
      this.value = value;
      this.selected = isSelected;
      setFont(l.getFont());
      return this;
    }

    @Override
    public Dimension getPreferredSize() {
      FontMetrics fm = getFontMetrics(getFont());
      return new Dimension(fm.stringWidth(value == null ? "" : value.text) + PAD * 2, fm.getHeight() + 4);
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = (Graphics2D) g0.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(selected ? Theme.getColor("editor.linehighlight.color") : textArea.getBackground());
        g.fillRect(0, 0, w, h);
        if (selected) {
          g.setColor(Theme.getColor("editor.brackethighlight.color"));
          g.fillRect(0, 0, 2, h);
        }
        if (value == null) return;

        SyntaxScheme scheme = textArea.getSyntaxScheme();
        Style style = scheme.getStyle(value.tokenType);
        Color fg = style != null && style.foreground != null ? style.foreground
          : scheme.getStyle(TokenTypes.IDENTIFIER).foreground;
        if (fg == null) fg = textArea.getForeground();
        Font font = style != null && style.font != null
          ? style.font.deriveFont((float) getFont().getSize2D()) : getFont();

        FontMetrics fm = g.getFontMetrics(font);
        int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
        int x = PAD;
        g.setFont(font);
        g.setColor(fg);
        g.drawString(value.text, x, baseline);
        if (value.insertsParens()) {
          g.setColor(textArea.getForeground());
          g.setFont(getFont());
          g.drawString("()", x + fm.stringWidth(value.text), baseline);
        }

        if (!value.source.isEmpty()) {
          Font small = getFont().deriveFont(getFont().getSize2D() * 0.85f);
          FontMetrics sfm = g.getFontMetrics(small);
          int lw = sfm.stringWidth(value.source);
          g.setFont(small);
          g.setColor(new Color(0x7e, 0x7e, 0x7e));
          g.drawString(value.source, w - PAD - lw, baseline);
        }
      } finally {
        g.dispose();
      }
    }
  }
}
