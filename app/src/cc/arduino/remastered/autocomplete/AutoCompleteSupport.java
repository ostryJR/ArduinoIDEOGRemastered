/*
 * This file is part of Arduino 1.8 Remastered.
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cc.arduino.remastered.autocomplete;

import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import processing.app.syntax.SketchTextArea;

/**
 * Code completion for one editor tab.
 * <ul>
 * <li>Opens by itself once a word reaches {@link #AUTO_MIN_CHARS} characters
 * and typing pauses for {@link #AUTO_DELAY_MS} ms, or at once with Ctrl+Space.</li>
 * <li>Up/Down/PageUp/PageDown choose, Enter or Tab insert, Esc closes; typing
 * keeps filtering. Enter and Tab behave normally while the list is closed.</li>
 * <li>Never opens inside comments or strings.</li>
 * </ul>
 */
public final class AutoCompleteSupport {

  static final int AUTO_MIN_CHARS = 3;
  static final int AUTO_DELAY_MS = 250;

  private static final Pattern INCLUDE_LINE = Pattern.compile("^\\s*#\\s*include\\s*([<\"])([\\w./+\\-]*)$");

  private final SketchTextArea textArea;
  private final Supplier<List<String>> sketchTexts;
  private final CompletionProvider provider = new CompletionProvider();
  private final CompletionPopup popup;
  private final Timer autoTimer;

  private boolean enabled = true;
  /** set while we change the document ourselves, so inserting doesn't re-trigger */
  private boolean inserting;

  // state of the open list
  private List<Completion> candidates = new ArrayList<>();
  private int wordStart = -1;
  private boolean headerMode;
  private char headerQuote;

  /**
   * @param sketchTexts supplies the text of every open tab of the sketch,
   *                    for completing the sketch's own variables and functions
   */
  public static AutoCompleteSupport install(SketchTextArea textArea, Supplier<List<String>> sketchTexts) {
    return new AutoCompleteSupport(textArea, sketchTexts);
  }

  private AutoCompleteSupport(SketchTextArea textArea, Supplier<List<String>> sketchTexts) {
    this.textArea = textArea;
    this.sketchTexts = sketchTexts;
    this.popup = new CompletionPopup(textArea, this::accept);

    autoTimer = new Timer(AUTO_DELAY_MS, e -> showIfWordLongEnough());
    autoTimer.setRepeats(false);

    // a KeyListener runs before the text area's key bindings, so consuming
    // here keeps Enter/Tab/arrows from reaching the editor while the list is open
    textArea.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        handleKeyPressed(e);
      }
    });
    attachDocumentListener(textArea.getDocument());
    textArea.addPropertyChangeListener("document", evt -> {
      hide();
      if (evt.getNewValue() instanceof Document) {
        attachDocumentListener((Document) evt.getNewValue());
      }
    });
    textArea.addCaretListener(e -> {
      if (popup.isVisible() && !inserting) {
        // caret moved by mouse or keys: refilter, or close when it left the word
        SwingUtilities.invokeLater(this::refresh);
      }
    });
    textArea.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        hide();
      }
    });
    textArea.addAncestorListener(new AncestorListener() {
      public void ancestorAdded(AncestorEvent event) { }
      public void ancestorRemoved(AncestorEvent event) { hide(); }
      public void ancestorMoved(AncestorEvent event) { hide(); }
    });
    textArea.addHierarchyListener(e -> {
      Window w = SwingUtilities.getWindowAncestor(textArea);
      if (w != null && !Boolean.TRUE.equals(textArea.getClientProperty(AutoCompleteSupport.class))) {
        textArea.putClientProperty(AutoCompleteSupport.class, Boolean.TRUE);
        w.addComponentListener(new ComponentAdapter() {
          @Override public void componentMoved(ComponentEvent ev) { hide(); }
          @Override public void componentResized(ComponentEvent ev) { hide(); }
        });
      }
    });
  }

  private final DocumentListener documentListener = new DocumentListener() {
    @Override
    public void insertUpdate(DocumentEvent e) {
      if (inserting || !enabled) return;
      if (popup.isVisible()) {
        SwingUtilities.invokeLater(AutoCompleteSupport.this::refresh);
      } else if (e.getLength() == 1) {
        autoTimer.restart(); // only for typing, not paste
      } else {
        autoTimer.stop();
      }
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      if (inserting) return;
      autoTimer.stop();
      if (popup.isVisible()) {
        SwingUtilities.invokeLater(AutoCompleteSupport.this::refresh);
      }
    }

    @Override
    public void changedUpdate(DocumentEvent e) { }
  };

  private Document listenedDocument;

  private void attachDocumentListener(Document doc) {
    if (listenedDocument != null) listenedDocument.removeDocumentListener(documentListener);
    listenedDocument = doc;
    if (doc != null) doc.addDocumentListener(documentListener);
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (!enabled) {
      autoTimer.stop();
      hide();
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  private void handleKeyPressed(KeyEvent e) {
    int code = e.getKeyCode();
    if (code == KeyEvent.VK_SPACE && e.isControlDown() && !e.isMetaDown() && !e.isAltDown()) {
      e.consume();
      if (enabled) {
        autoTimer.stop();
        showCompletions(true);
      }
      return;
    }
    if (!popup.isVisible()) {
      return;
    }
    if (e.isMetaDown() || e.isAltDown() || e.isControlDown()) {
      if (code != KeyEvent.VK_SHIFT && code != KeyEvent.VK_META && code != KeyEvent.VK_ALT
          && code != KeyEvent.VK_CONTROL) {
        hide(); // shortcuts (Cmd+S, ...) close the list and run normally
      }
      return;
    }
    switch (code) {
      case KeyEvent.VK_DOWN: popup.move(1); e.consume(); break;
      case KeyEvent.VK_UP: popup.move(-1); e.consume(); break;
      case KeyEvent.VK_PAGE_DOWN: popup.move(popup.pageSize()); e.consume(); break;
      case KeyEvent.VK_PAGE_UP: popup.move(-popup.pageSize()); e.consume(); break;
      case KeyEvent.VK_ENTER:
      case KeyEvent.VK_TAB:
        if (popup.getSelected() != null) {
          accept(popup.getSelected());
          e.consume();
        }
        break;
      case KeyEvent.VK_ESCAPE: hide(); e.consume(); break;
      case KeyEvent.VK_HOME: case KeyEvent.VK_END: case KeyEvent.VK_LEFT: case KeyEvent.VK_RIGHT:
        // let the caret move; the caret listener closes or refilters
        break;
      default:
        break;
    }
  }

  private void showIfWordLongEnough() {
    if (enabled && textArea.isFocusOwner() && textArea.getSelectionStart() == textArea.getSelectionEnd()) {
      showCompletions(false);
    }
  }

  /** Works out what is being typed at the caret and opens the list for it. */
  private void showCompletions(boolean explicit) {
    Context ctx = contextAtCaret();
    if (ctx == null) {
      hide();
      return;
    }
    if (!explicit && ctx.prefix.length() < AUTO_MIN_CHARS) {
      return;
    }
    headerMode = ctx.header;
    headerQuote = ctx.quote;
    wordStart = ctx.start;
    if (headerMode) {
      candidates = provider.headerCandidates();
    } else {
      candidates = provider.identifierCandidates(textArea.getKeywords(), sketchTexts.get());
    }
    if (!explicit) {
      // the word is already complete (typed "int", "HIGH", ...): don't pop up
      // uninvited, or a following Enter would replace it
      for (Completion c : candidates) {
        if (c.text.equals(ctx.prefix)) return;
      }
    }
    List<Completion> matches = CompletionProvider.filter(candidates, ctx.prefix);
    if (matches.isEmpty()) {
      hide();
      return;
    }
    popup.show(matches, wordStart);
  }

  /** Called after each edit or caret move while the list is open. */
  private void refresh() {
    if (!popup.isVisible()) return;
    Context ctx = contextAtCaret();
    if (ctx == null || ctx.start != wordStart || ctx.header != headerMode || ctx.prefix.isEmpty()) {
      hide();
      return;
    }
    List<Completion> matches = CompletionProvider.filter(candidates, ctx.prefix);
    if (matches.isEmpty()) {
      hide();
    } else {
      popup.show(matches, wordStart);
    }
  }

  private void hide() {
    autoTimer.stop();
    popup.hide();
  }

  private void accept(Completion c) {
    Context ctx = contextAtCaret();
    hide();
    if (ctx == null || c == null) return;
    int caret = textArea.getCaretPosition();
    Document doc = textArea.getDocument();
    inserting = true;
    textArea.beginAtomicEdit();
    try {
      // replace the whole word under the caret, also the part after it
      int end = caret;
      while (end < doc.getLength() && isWordChar(doc.getText(end, 1).charAt(0), ctx.header)) end++;
      doc.remove(ctx.start, end - ctx.start);
      String text = c.text;
      int caretAfter = ctx.start + text.length();
      if (ctx.header) {
        char close = ctx.quote == '<' ? '>' : '"';
        boolean hasClose = ctx.start < doc.getLength() && doc.getText(ctx.start, 1).charAt(0) == close;
        if (!hasClose) text += close;
        caretAfter = ctx.start + text.length() + (hasClose ? 1 : 0);
      } else if (c.insertsParens()) {
        boolean hasParen = ctx.start < doc.getLength() && doc.getText(ctx.start, 1).charAt(0) == '(';
        if (!hasParen) {
          text += "()";
        }
        caretAfter = ctx.start + c.text.length() + 1; // inside the parentheses
      }
      doc.insertString(ctx.start, text, null);
      textArea.setCaretPosition(Math.min(caretAfter, doc.getLength()));
    } catch (BadLocationException ex) {
      // document changed under us: nothing sensible to insert
    } finally {
      textArea.endAtomicEdit();
      inserting = false;
    }
  }

  private static boolean isWordChar(char ch, boolean header) {
    return Character.isLetterOrDigit(ch) || ch == '_'
      || (header && (ch == '.' || ch == '/' || ch == '+' || ch == '-'));
  }

  /** What is being completed at the caret. */
  private static final class Context {
    final int start;
    final String prefix;
    final boolean header;
    final char quote;

    Context(int start, String prefix, boolean header, char quote) {
      this.start = start;
      this.prefix = prefix;
      this.header = header;
      this.quote = quote;
    }
  }

  private Context contextAtCaret() {
    int caret = textArea.getCaretPosition();
    Document doc = textArea.getDocument();
    Element root = doc.getDefaultRootElement();
    Element line = root.getElement(root.getElementIndex(caret));
    String before;
    try {
      before = doc.getText(line.getStartOffset(), caret - line.getStartOffset());
    } catch (BadLocationException e) {
      return null;
    }

    Matcher inc = INCLUDE_LINE.matcher(before);
    if (inc.find()) {
      String prefix = inc.group(2);
      return new Context(caret - prefix.length(), prefix, true, inc.group(1).charAt(0));
    }

    int i = before.length();
    while (i > 0 && isWordChar(before.charAt(i - 1), false)) i--;
    // "#inc" completes preprocessor keywords
    if (i > 0 && before.charAt(i - 1) == '#' && before.substring(0, i - 1).trim().isEmpty()) i--;
    String prefix = before.substring(i);
    if (!prefix.isEmpty() && Character.isDigit(prefix.charAt(0))) return null; // a number
    if (prefix.isEmpty() && !popup.isVisible()) {
      // Ctrl+Space on an empty word: offer everything
      if (insideCommentOrString(caret)) return null;
      return new Context(caret, "", false, '\0');
    }
    if (insideCommentOrString(caret)) return null;
    return new Context(line.getStartOffset() + i, prefix, false, '\0');
  }

  private boolean insideCommentOrString(int caret) {
    if (caret == 0) return false;
    Token t = textArea.modelToToken(caret - 1);
    if (t == null) return false;
    if (t.isComment()) return true;
    int type = t.getType();
    return type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE || type == TokenTypes.LITERAL_CHAR
      || type == TokenTypes.ERROR_STRING_DOUBLE || type == TokenTypes.ERROR_CHAR;
  }
}
