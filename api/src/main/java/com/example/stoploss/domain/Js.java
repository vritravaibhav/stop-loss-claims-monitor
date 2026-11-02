package com.example.stoploss.domain;

import java.util.Locale;

/**
 * The few JavaScript string semantics the rules were written against, so parsing gives the same answers as the
 * reference implementation: {@code String.prototype.trim()} and regex {@code \s} use the ECMAScript WhiteSpace and
 * LineTerminator sets (which include U+00A0 and U+FEFF), unlike Java's {@code trim()}/{@code strip()}/{@code \s}.
 */
public final class Js {
  private Js() {}

  /** ECMAScript WhiteSpace + LineTerminator code units. */
  public static boolean isSpace(char c) {
    return switch (c) {
      case '\t', '\n', '\u000B', '\f', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　',
          '﻿' -> true;
      default -> c >= ' ' && c <= ' ';
    };
  }

  /** {@code String.prototype.trim()}. */
  public static String trim(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isSpace(s.charAt(start))) start++;
    while (end > start && isSpace(s.charAt(end - 1))) end--;
    return s.substring(start, end);
  }

  /** {@code s.replace(/\s+/g, '')}. */
  public static String removeSpaces(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!isSpace(c)) out.append(c);
    }
    return out.toString();
  }

  public static String upper(String s) {
    return s.toUpperCase(Locale.ROOT);
  }

  public static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  /** {@code s.charAt(0).toUpperCase() + s.slice(1)}. */
  public static String capitalise(String s) {
    if (s.isEmpty()) return s;
    return upper(s.substring(0, 1)) + s.substring(1);
  }

  /** True if every char is ASCII 0-9 and there is at least one ({@code /^\d+$/}). */
  public static boolean isDigits(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }
}
