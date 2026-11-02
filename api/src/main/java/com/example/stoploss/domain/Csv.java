package com.example.stoploss.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reader that works one <em>physical line</em> at a time.
 *
 * <p>Why not a CSV library: the analyst needs the physical line number of every bad row, a malformed line must only
 * fail that line (not swallow the rest of the file), and the sample files mix line endings (the {@code #} comment
 * line of TPA Two files ends in LF, the rest in CRLF). Splitting lines first and then parsing each line gives all
 * three. Quoted fields containing line breaks are not supported (claim files never contain them); such a line is
 * reported as a malformed row.
 */
public final class Csv {
  private Csv() {}

  /** Cells of one line, or the reason it could not be read. */
  public record Line(List<String> cells, String error) {
    public boolean ok() {
      return error == null;
    }

    static Line ok(List<String> cells) {
      return new Line(List.copyOf(cells), null);
    }

    static Line failed(String reason) {
      return new Line(List.of(), reason);
    }
  }

  /** Split file text into physical lines; accepts CRLF, LF and lone CR. Line i of the result is file line i + 1. */
  public static List<String> splitLines(String text) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    int n = text.length();
    for (int i = 0; i < n; i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n') {
        lines.add(text.substring(start, i));
        if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
        start = i + 1;
      }
    }
    lines.add(text.substring(start));
    // A trailing line break does not start another line.
    if (lines.getLast().isEmpty()) lines.removeLast();
    return lines;
  }

  /**
   * Decode file bytes as UTF-8 (malformed sequences become U+FFFD) and drop a leading byte-order mark. Like the
   * reference implementation (a WHATWG TextDecoder, which consumes one BOM, followed by an explicit check), up to two
   * leading BOMs are removed.
   */
  public static String decodeText(byte[] content) {
    int offset = 0;
    if (content.length >= 3 && (content[0] & 0xff) == 0xef && (content[1] & 0xff) == 0xbb && (content[2] & 0xff) == 0xbf) {
      offset = 3;
    }
    return decodeText(new String(content, offset, content.length - offset, StandardCharsets.UTF_8));
  }

  /** Drop one leading byte-order mark from already decoded text. */
  public static String decodeText(String text) {
    return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
  }

  /**
   * Parse one CSV line into raw (untrimmed) cells. Fields may be quoted with {@code "}; {@code ""} inside a quoted
   * field is a literal quote; whitespace around a quoted field is tolerated. A quote in the middle of an unquoted field
   * is kept literally.
   */
  public static Line parseLine(String line) {
    List<String> cells = new ArrayList<>();
    int i = 0;
    int n = line.length();
    for (;;) {
      // Skip leading whitespace to detect a quoted field, but keep it for unquoted ones.
      int j = i;
      while (j < n && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j++;
      if (j < n && line.charAt(j) == '"') {
        StringBuilder value = new StringBuilder();
        int k = j + 1;
        boolean closed = false;
        while (k < n) {
          char ch = line.charAt(k);
          if (ch == '"') {
            if (k + 1 < n && line.charAt(k + 1) == '"') {
              value.append('"');
              k += 2;
              continue;
            }
            closed = true;
            k++;
            break;
          }
          value.append(ch);
          k++;
        }
        if (!closed) return Line.failed("malformed CSV: unterminated quoted field");
        while (k < n && (line.charAt(k) == ' ' || line.charAt(k) == '\t')) k++;
        if (k < n && line.charAt(k) != ',') return Line.failed("malformed CSV: unexpected text after a quoted field");
        cells.add(value.toString());
        if (k >= n) return Line.ok(cells);
        i = k + 1;
      } else {
        int comma = line.indexOf(',', i);
        if (comma == -1) {
          cells.add(line.substring(i));
          return Line.ok(cells);
        }
        cells.add(line.substring(i, comma));
        i = comma + 1;
      }
    }
  }
}
