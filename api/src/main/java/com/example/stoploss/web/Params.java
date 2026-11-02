package com.example.stoploss.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/** Small request-parameter parsers (callers decide 400 vs 404). */
public final class Params {
  private Params() {}

  private static final Pattern ID = Pattern.compile("[1-9]\\d{0,14}");

  /** Positive integer id from a path segment / query value, else null. */
  public static Long parseId(String value) {
    if (value == null || !ID.matcher(value).matches()) return null;
    return Long.parseLong(value);
  }

  /** The query parameter's value if it was given exactly once, else null (absent or repeated). */
  public static String single(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    return values != null && values.length == 1 ? values[0] : null;
  }
}
