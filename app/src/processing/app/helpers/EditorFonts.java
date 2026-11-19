/*
 * This file is part of Arduino IDE 1.8 Remastered.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 */

package processing.app.helpers;

import org.apache.commons.lang3.StringUtils;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.PreferencesData;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Remastered: the editor fonts offered in Edit &gt; Editor Font.
 * <p>
 * JetBrains Mono ships with the IDE (lib/fonts, SIL OFL). SF Mono is loaded
 * from the copy macOS keeps inside Terminal.app (Apple's license does not
 * allow bundling it). Monaco is a regular system font.
 */
public class EditorFonts {

  public static final String JETBRAINS_MONO = "JetBrains Mono";
  public static final String SF_MONO = "SF Mono";
  public static final String MONACO = "Monaco";

  public static final List<String> CHOICES = Arrays.asList(JETBRAINS_MONO, SF_MONO, MONACO);

  private static final String[] SF_MONO_DIRS = {
    "/System/Applications/Utilities/Terminal.app/Contents/Resources/Fonts",
    "/Applications/Utilities/Terminal.app/Contents/Resources/Fonts",
  };
  private static final String[] SF_MONO_FILES = {
    "SF-Mono-Regular.otf", "SF-Mono-Bold.otf", "SF-Mono-RegularItalic.otf", "SF-Mono-BoldItalic.otf",
  };
  private static final String[] JETBRAINS_FILES = {
    "JetBrainsMono-Regular.ttf", "JetBrainsMono-Bold.ttf", "JetBrainsMono-Italic.ttf", "JetBrainsMono-BoldItalic.ttf",
  };

  private static boolean registered = false;

  /**
   * Registers the bundled and system-provided fonts with AWT so that
   * <code>new Font("JetBrains Mono", ...)</code> and
   * <code>new Font("SF Mono", ...)</code> resolve. Safe to call repeatedly.
   */
  public static synchronized void register() {
    if (registered) {
      return;
    }
    registered = true;
    File fontsDir = new File(BaseNoGui.getContentFile("lib"), "fonts");
    for (String name : JETBRAINS_FILES) {
      registerFile(new File(fontsDir, name));
    }
    if (OSUtils.isMacOS()) {
      for (String dir : SF_MONO_DIRS) {
        if (new File(dir, SF_MONO_FILES[0]).isFile()) {
          for (String name : SF_MONO_FILES) {
            registerFile(new File(dir, name));
          }
          break;
        }
      }
    }
  }

  private static void registerFile(File file) {
    if (!file.isFile()) {
      return;
    }
    try {
      GraphicsEnvironment.getLocalGraphicsEnvironment()
        .registerFont(Font.createFont(Font.TRUETYPE_FONT, file));
    } catch (Exception e) {
      System.err.println("Could not load font " + file + ": " + e.getMessage());
    }
  }

  /** @return true when the given family can actually be rendered. */
  public static boolean isAvailable(String family) {
    register();
    return family.equals(new Font(family, Font.PLAIN, 12).getFamily());
  }

  /** @return the family part of the current editor.font preference. */
  public static String current() {
    String value = StringUtils.defaultString(PreferencesData.get("editor.font"));
    return value.split(",")[0].trim();
  }

  /**
   * Switch the editor font family, keeping style and size, and apply it to
   * every open window right away.
   */
  public static void select(String family) {
    String value = StringUtils.defaultString(PreferencesData.get("editor.font"), "Monaco,plain,12");
    String[] pieces = value.split(",");
    if (pieces.length != 3) {
      pieces = new String[]{family, "plain", "12"};
    }
    pieces[0] = family;
    PreferencesData.set("editor.font", String.join(",", pieces));
    Base.INSTANCE.getEditors().forEach(Editor::applyPreferences);
  }
}
