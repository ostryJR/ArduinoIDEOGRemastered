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

  /** Longest label shown in the legend. */
  public static final int MAX_LABEL = 32;

  /**
   * Parses one value field.
   *
   * @return the value, {@link Double#NaN} for a field that is a number slot
   * without a usable value (Arduino's <code>Serial.print(float)</code> prints
   * <code>nan</code>, <code>inf</code> or <code>ovf</code>), or null if the
   * field is text
   */
  public static Double parseValue(String field) {
    String f = field.trim();
    if (f.isEmpty()) {
      return null;
    }
    String l = f.toLowerCase();
    if (l.equals("nan") || l.equals("ovf") || l.equals("inf") || l.equals("-inf") || l.equals("+inf")) {
      return Double.NaN;
    }
    try {
      double v = Double.parseDouble(f);
      return Double.isFinite(v) ? v : Double.NaN;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Remastered: CSV-style header. A line whose fields are all text (no
   * numbers, no <code>name:value</code> pairs) names the columns, so a sketch
   * can print <code>Temp,Humidity</code> once in <code>setup()</code> and then
   * only <code>21.5,40</code>.
   * <p>
   * In Auto mode a header that contains commas or tabs is split only on those,
   * so names may contain spaces (<code>Temp C,Humidity %</code>).
   *
   * @return the column names, or null if the line is not a header
   */
  public static String[] headerLabels(String line) {
    refresh();
    String[] fields = cachedValues.split(line);
    boolean any = false;
    for (String f : fields) {
      if (f.isEmpty()) {
        continue;
      }
      if (f.indexOf(cachedLabelChar) >= 0 || parseValue(f) != null) {
        return null;
      }
      any = true;
    }
    if (!any) {
      return null;
    }
    if (separator() == Separator.AUTO) {
      if (line.indexOf(',') >= 0) {
        fields = line.split("\\s*,\\s*");
      } else if (line.indexOf('\t') >= 0) {
        fields = line.split("\\s*\\t\\s*");
      }
    }
    for (int i = 0; i < fields.length; i++) {
      String f = fields[i].trim();
      fields[i] = f.length() > MAX_LABEL ? f.substring(0, MAX_LABEL) : f;
    }
    return fields;
  }
}
