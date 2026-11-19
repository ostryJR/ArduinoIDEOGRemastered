/*
 * This file is part of Arduino 1.8 Remastered.
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cc.arduino.remastered.autocomplete;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fife.ui.rsyntaxtextarea.TokenTypes;

import processing.app.BaseNoGui;
import processing.app.debug.TargetPlatform;
import processing.app.packages.UserLibrary;
import processing.app.syntax.PdeKeywords;

/**
 * Collects completion candidates: keywords.txt entries (core, board platform,
 * installed libraries), symbols declared in the open sketch, and header names
 * for #include. No compiler involved: everything is cheap text scanning.
 */
final class CompletionProvider {

  static final int MAX_RESULTS = 200;

  /** C/C++ words that look like "type name" or "name(" but are not declarations. */
  private static final Set<String> NOT_A_NAME = new HashSet<>(Arrays.asList(
    "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break",
    "continue", "goto", "sizeof", "new", "delete", "using", "namespace", "template",
    "typename", "public", "private", "protected", "operator", "throw", "catch", "try",
    "const", "static", "volatile", "extern", "inline", "virtual", "unsigned", "signed",
    "long", "short", "struct", "class", "enum", "union", "typedef", "void", "int", "char",
    "float", "double", "bool", "boolean", "byte", "word", "auto", "register", "friend",
    "explicit", "constexpr", "this", "true", "false", "nullptr", "NULL", "define",
    "include", "ifdef", "ifndef", "endif", "pragma", "undef", "defined"));

  // "type name" followed by = ; , [ ( ) : declarations, parameters, prototypes
  private static final Pattern DECLARATION = Pattern.compile(
    "\\b([A-Za-z_]\\w*)(?:\\s*<[^;{}<>]*>)?(?:\\s*[*&]+\\s*|\\s+)([A-Za-z_]\\w*)\\s*(?=[=;,\\[()])");
  // name(...) { : function definitions (including Class::method)
  private static final Pattern FUNCTION_DEF = Pattern.compile(
    "\\b([A-Za-z_]\\w*)\\s*\\([^;{}()]*(?:\\([^;{}()]*\\)[^;{}()]*)*\\)\\s*(?:const\\s*)?(?:override\\s*)?\\{");
  private static final Pattern DEFINE = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)");
  private static final Pattern TYPE_DEF = Pattern.compile(
    "\\b(?:class|struct|union|enum(?:\\s+class)?)\\s+([A-Za-z_]\\w*)");
  private static final Pattern TYPEDEF = Pattern.compile("\\btypedef\\b[^;{]*?\\b([A-Za-z_]\\w*)\\s*;");
  private static final Pattern ENUM_BODY = Pattern.compile(
    "\\benum\\b(?:\\s+class)?(?:\\s+[A-Za-z_]\\w*)?(?:\\s*:\\s*[\\w\\s]+)?\\s*\\{([^}]*)\\}");
  private static final Pattern ENUM_VALUE = Pattern.compile("(?:^|,)\\s*([A-Za-z_]\\w*)");

  private List<Completion> headerCache;
  private Object headerCacheKey;

  /**
   * All identifier candidates (unfiltered).
   *
   * @param sketchTexts text of every open tab of the sketch
   */
  List<Completion> identifierCandidates(PdeKeywords keywords, Collection<String> sketchTexts) {
    Map<String, Completion> result = new LinkedHashMap<>();

    // sketch symbols first: on duplicates the sketch's own meaning wins
    Set<String> functions = new HashSet<>();
    Set<String> symbols = new HashSet<>();
    for (String text : sketchTexts) {
      scanSketch(stripCommentsAndStrings(text), functions, symbols);
    }
    for (String name : functions) {
      result.put(name, new Completion(name, Completion.Kind.SKETCH_FUNCTION, TokenTypes.IDENTIFIER, "sketch"));
    }
    for (String name : symbols) {
      if (!result.containsKey(name)) {
        result.put(name, new Completion(name, Completion.Kind.SKETCH_SYMBOL, TokenTypes.IDENTIFIER, "sketch"));
      }
    }

    if (keywords != null) {
      for (Map.Entry<String, String> e : keywords.getKeywordTypes().entrySet()) {
        String word = e.getKey();
        int tokenType = PdeKeywords.tokenTypeFor(e.getValue());
        Completion existing = result.get(word);
        if (existing != null) {
          // declared in the sketch and a known keyword: keep the sketch kind, but color like the editor
          result.put(word, new Completion(word, existing.kind, tokenType, existing.source));
          continue;
        }
        Completion.Kind kind = "FUNCTION".equals(e.getValue()) && !word.startsWith("#")
          ? Completion.Kind.FUNCTION : Completion.Kind.KEYWORD;
        result.put(word, new Completion(word, kind, tokenType, keywords.getKeywordSource(word)));
      }
    }
    return new ArrayList<>(result.values());
  }

  /** Header names for #include: installed libraries (incl. platform ones) and the board's cores. */
  List<Completion> headerCandidates() {
    TargetPlatform platform;
    try {
      platform = BaseNoGui.getTargetPlatform();
    } catch (RuntimeException e) {
      platform = null; // boards not loaded (yet)
    }
    Object key = Arrays.asList(platform, BaseNoGui.librariesIndexer == null ? null
      : BaseNoGui.librariesIndexer.getInstalledLibraries().size());
    if (headerCache != null && key.equals(headerCacheKey)) {
      return headerCache;
    }
    Map<String, Completion> result = new LinkedHashMap<>();
    if (BaseNoGui.librariesIndexer != null) {
      for (UserLibrary lib : BaseNoGui.librariesIndexer.getInstalledLibraries()) {
        File src = lib.getSrcFolder();
        for (String h : headersIn(src)) {
          result.putIfAbsent(h, new Completion(h, Completion.Kind.HEADER, TokenTypes.IDENTIFIER, lib.getName()));
        }
      }
    }
    if (platform != null) {
      File[] cores = new File(platform.getFolder(), "cores").listFiles(File::isDirectory);
      if (cores != null) {
        for (File core : cores) {
          for (String h : headersIn(core)) {
            result.putIfAbsent(h, new Completion(h, Completion.Kind.HEADER, TokenTypes.IDENTIFIER, "core"));
          }
        }
      }
    }
    headerCache = new ArrayList<>(result.values());
    headerCacheKey = key;
    return headerCache;
  }

  private static List<String> headersIn(File dir) {
    List<String> names = new ArrayList<>();
    String[] files = dir == null ? null : dir.list();
    if (files != null) {
      for (String f : files) {
        String lower = f.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".h") || lower.endsWith(".hpp")) {
          names.add(f);
        }
      }
    }
    return names;
  }

  /**
   * Filters and ranks candidates for a prefix: case-sensitive prefix, then
   * case-insensitive prefix, then camel-hump/substring matches. Within each
   * group sketch symbols come first, then shorter and alphabetical.
   */
  static List<Completion> filter(List<Completion> candidates, String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    List<Completion> exact = new ArrayList<>();
    List<Completion> prefixCi = new ArrayList<>();
    List<Completion> fuzzy = new ArrayList<>();
    for (Completion c : candidates) {
      if (c.text.equals(prefix)) {
        continue; // nothing left to complete
      }
      if (c.text.startsWith(prefix)) {
        exact.add(c);
      } else if (c.text.toLowerCase(Locale.ROOT).startsWith(lower)) {
        prefixCi.add(c);
      } else if (camelHumps(c.text, prefix) || (prefix.length() >= 3 && c.text.toLowerCase(Locale.ROOT).contains(lower))) {
        fuzzy.add(c);
      }
    }
    List<Completion> out = new ArrayList<>(exact.size() + prefixCi.size() + fuzzy.size());
    for (List<Completion> group : Arrays.asList(exact, prefixCi, fuzzy)) {
      group.sort((a, b) -> {
        int sa = isSketch(a) ? 0 : 1, sb = isSketch(b) ? 0 : 1;
        if (sa != sb) return sa - sb;
        int c = a.text.compareToIgnoreCase(b.text);
        return c != 0 ? c : a.text.compareTo(b.text);
      });
      for (Completion c : group) {
        if (out.size() >= MAX_RESULTS) return out;
        out.add(c);
      }
    }
    return out;
  }

  private static boolean isSketch(Completion c) {
    return c.kind == Completion.Kind.SKETCH_FUNCTION || c.kind == Completion.Kind.SKETCH_SYMBOL;
  }

  /** "dW" matches "digitalWrite", "aR" matches "analogRead". */
  private static boolean camelHumps(String text, String prefix) {
    if (prefix.length() < 2 || Character.toLowerCase(text.charAt(0)) != Character.toLowerCase(prefix.charAt(0))) {
      return false;
    }
    int t = 1;
    for (int p = 1; p < prefix.length(); p++) {
      char want = prefix.charAt(p);
      boolean found = false;
      for (; t < text.length(); t++) {
        char ch = text.charAt(t);
        char prev = text.charAt(t - 1);
        boolean hump = prev == '_' || (Character.isUpperCase(ch) && !Character.isUpperCase(prev));
        if (hump && Character.toLowerCase(ch) == Character.toLowerCase(want)) {
          found = true;
          t++;
          break;
        }
      }
      if (!found) return false;
    }
    return true;
  }

  private static void scanSketch(String code, Set<String> functions, Set<String> symbols) {
    Matcher m = FUNCTION_DEF.matcher(code);
    while (m.find()) {
      addName(functions, m.group(1));
    }
    m = DECLARATION.matcher(code);
    while (m.find()) {
      if (NOT_A_NAME.contains(m.group(1)) && !isTypeKeyword(m.group(1))) continue;
      String name = m.group(2);
      // "type name(...);" outside a definition: a prototype (void f(int);) is a
      // function, a constructor call (Servo s(9);) is a variable
      if (code.charAt(m.end()) == '(' && !functions.contains(name)) {
        int close = code.indexOf(')', m.end());
        if (close >= 0) {
          String args = code.substring(m.end() + 1, close).trim();
          String first = args.split("[^\\w]", 2)[0];
          if (args.isEmpty() || isTypeKeyword(first) || args.matches("\\w+(?:\\s*<[^>]*>)?[\\s*&]+\\w+.*")) {
            addName(functions, name);
            continue;
          }
        }
      }
      addName(symbols, name);
    }
    m = DEFINE.matcher(code);
    while (m.find()) addName(symbols, m.group(1));
    m = TYPE_DEF.matcher(code);
    while (m.find()) addName(symbols, m.group(1));
    m = TYPEDEF.matcher(code);
    while (m.find()) addName(symbols, m.group(1));
    m = ENUM_BODY.matcher(code);
    while (m.find()) {
      Matcher v = ENUM_VALUE.matcher(m.group(1));
      while (v.find()) addName(symbols, v.group(1));
    }
  }

  private static boolean isTypeKeyword(String word) {
    switch (word) {
      case "const": case "static": case "volatile": case "extern": case "unsigned": case "signed":
      case "long": case "short": case "struct": case "class": case "enum": case "union":
      case "void": case "int": case "char": case "float": case "double": case "bool":
      case "boolean": case "byte": case "word": case "auto": case "register": case "constexpr":
        return true;
      default:
        return false;
    }
  }

  private static void addName(Set<String> into, String name) {
    if (name != null && name.length() > 1 && !NOT_A_NAME.contains(name)) {
      into.add(name);
    }
  }

  /** Replaces comments and string/char literal contents with spaces (keeps line structure). */
  static String stripCommentsAndStrings(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    int n = text.length();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : '\0';
      if (c == '/' && next == '/') {
        while (i < n && text.charAt(i) != '\n') { sb.append(' '); i++; }
      } else if (c == '/' && next == '*') {
        sb.append("  ");
        i += 2;
        while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
          sb.append(text.charAt(i) == '\n' ? '\n' : ' ');
          i++;
        }
        if (i < n) { sb.append("  "); i += 2; }
      } else if (c == '"' || c == '\'') {
        sb.append(c);
        i++;
        while (i < n && text.charAt(i) != c && text.charAt(i) != '\n') {
          if (text.charAt(i) == '\\' && i + 1 < n) { sb.append(' '); i++; }
          sb.append(' ');
          i++;
        }
        if (i < n && text.charAt(i) == c) { sb.append(c); i++; }
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }
}
