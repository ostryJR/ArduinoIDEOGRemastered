/*
 * This file is part of Arduino IDE 1.8 Remastered.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 */

package processing.app.helpers;

import processing.app.PreferencesData;

import java.util.regex.Pattern;

import static processing.app.I18n.tr;

/**
 * Remastered: how the Serial Plotter splits an incoming line into values,
 * and labels from values (File &gt; Preferences &gt; Serial Plotter).
 * <p>
 * The defaults reproduce stock 1.8.19 exactly: values separated by commas,
 * spaces or tabs, and labels written as <code>name:value</code>.
 */
public class PlotterFormat {

  public static final String PREF_SEPARATOR = "serial.plotter.separator";
  public static final String PREF_CUSTOM = "serial.plotter.separator.custom";
  public static final String PREF_LABEL = "serial.plotter.label_separator";

  public enum Separator {
    AUTO("auto", "Auto (comma, space or tab)", "[, \\t]+"),
    COMMA("comma", "Comma  ,", "\\s*,\\s*"),
    SEMICOLON("semicolon", "Semicolon  ;", "\\s*;\\s*"),
    SPACE("space", "Space", " +"),
    TAB("tab", "Tab", "\\t+"),
    CUSTOM("custom", "Custom:", null);

    public final String key;
    private final String title;
    private final String regex;

    Separator(String key, String title, String regex) {
      this.key = key;
      this.title = title;
      this.regex = regex;
    }

    @Override
    public String toString() {
      return tr(title);
    }

    public static Separator fromKey(String key) {
      for (Separator s : values()) {
        if (s.key.equals(key)) {
          return s;
        }
      }
      return AUTO;
    }
  }

  /** Label separators offered in the preferences, as shown to the user. */
  public static final String[] LABEL_STYLES = {"name:value", "name=value"};

  private static String cachedKey;
  private static Pattern cachedValues;
  private static Pattern cachedLabels;
  private static char cachedLabelChar;

  public static Separator separator() {
    return Separator.fromKey(PreferencesData.get(PREF_SEPARATOR, "auto"));
  }

  public static String customSeparator() {
    return PreferencesData.get(PREF_CUSTOM, "");
  }

  /** @return ':' or '=' */
  public static char labelSeparator() {
    return "=".equals(PreferencesData.get(PREF_LABEL, ":")) ? '=' : ':';
  }

  private static synchronized void refresh() {
    Separator sep = separator();
    String custom = customSeparator();
    char label = labelSeparator();
    String key = sep.key + "\u0000" + custom + "\u0000" + label;
    if (key.equals(cachedKey)) {
      return;
    }
    String regex = sep.regex;
    if (sep == Separator.CUSTOM) {
      // literal text, e.g. "|" or " / "; surrounding blanks are ignored
      regex = custom.trim().isEmpty() ? Separator.AUTO.regex : "\\s*" + Pattern.quote(custom.trim()) + "\\s*";
    }
    cachedValues = Pattern.compile(regex);
    cachedLabels = Pattern.compile("[" + label + "]+"); // ':' or '=', literal in a class
    cachedLabelChar = label;
    cachedKey = key;
  }

  /** Splits a (trimmed) line into its fields. */
  public static String[] splitValues(String line) {
    refresh();
    return cachedValues.split(line);
  }

  /** @return true if the field is written as name/value pair */
  public static boolean hasLabel(String field) {
    refresh();
    return field.indexOf(cachedLabelChar) >= 0;
  }

  /** Splits a "name:value" field into name and value. */
  public static String[] splitLabel(String field) {
    refresh();
    return cachedLabels.split(field);
  }
}
