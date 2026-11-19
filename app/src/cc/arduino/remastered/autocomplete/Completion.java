/*
 * This file is part of Arduino 1.8 Remastered.
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package cc.arduino.remastered.autocomplete;

/** One suggestion shown in the completion popup. */
public final class Completion {

  public enum Kind {
    /** keywords.txt entry that is a function/method: inserted with "()" */
    FUNCTION,
    /** any other keywords.txt entry (types, constants, C++ keywords) */
    KEYWORD,
    /** function defined in the sketch: inserted with "()" */
    SKETCH_FUNCTION,
    /** variable, macro, type or enum value declared in the sketch */
    SKETCH_SYMBOL,
    /** header file name after #include */
    HEADER
  }

  final String text;
  final Kind kind;
  /** RSyntaxTextArea TokenTypes value used to color the entry like the editor does */
  final int tokenType;
  /** short dim label on the right (library name, "sketch"), may be empty */
  final String source;

  Completion(String text, Kind kind, int tokenType, String source) {
    this.text = text;
    this.kind = kind;
    this.tokenType = tokenType;
    this.source = source == null ? "" : source;
  }

  boolean insertsParens() {
    return kind == Kind.FUNCTION || kind == Kind.SKETCH_FUNCTION;
  }

  @Override
  public String toString() {
    return text;
  }
}
