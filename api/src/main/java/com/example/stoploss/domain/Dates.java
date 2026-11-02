package com.example.stoploss.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calendar dates are handled as {@code YYYY-MM-DD} strings (no time zone), which is also how the API returns them.
 * Timestamps go out as ISO-8601 UTC with milliseconds ({@code 2026-11-02T09:15:00.123Z}).
 */
public final class Dates {
  private Dates() {}

  private static final Pattern US = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})"); // MM/DD/YYYY (tpa_one)
  private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})"); // YYYY-MM-DD (tpa_two)
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** A YYYY-MM-DD date, or the reason the input was rejected. */
  public record DateResult(String date, String error) {
    public boolean ok() {
      return error == null;
    }
  }

  public static boolean isLeapYear(int y) {
    return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
  }

  public static int daysInMonth(int y, int m) {
    return switch (m) {
      case 1, 3, 5, 7, 8, 10, 12 -> 31;
      case 4, 6, 9, 11 -> 30;
      case 2 -> isLeapYear(y) ? 29 : 28;
      default -> 0;
    };
  }

  /** An ISO date string if (y, m, d) is a real calendar date in a sane range, else null. */
  public static String isoDate(int y, int m, int d) {
    if (y < 1900 || y > 2200) return null;
    if (m < 1 || m > 12) return null;
    if (d < 1 || d > daysInMonth(y, m)) return null;
    return "%04d-%02d-%02d".formatted(y, m, d);
  }

  /**
   * Parse {@code MM/DD/YYYY} or {@code YYYY-MM-DD} into {@code YYYY-MM-DD}; the result must be a real calendar date
   * ({@code 02/30/2026}, {@code 2026-13-01} are rejected). {@code label} names the field in the error message.
   */
  public static DateResult parseDate(String input, String label) {
    String s = Js.trim(input);
    if (s.isEmpty()) return new DateResult(null, label + " is blank");
    int y;
    int m;
    int d;
    Matcher match = US.matcher(s);
    if (match.matches()) {
      m = Integer.parseInt(match.group(1));
      d = Integer.parseInt(match.group(2));
      y = Integer.parseInt(match.group(3));
    } else if ((match = ISO.matcher(s)).matches()) {
      y = Integer.parseInt(match.group(1));
      m = Integer.parseInt(match.group(2));
      d = Integer.parseInt(match.group(3));
    } else {
      return new DateResult(null, "invalid " + label + " \"" + s + "\" (expected MM/DD/YYYY or YYYY-MM-DD)");
    }
    String iso = isoDate(y, m, d);
    if (iso == null) return new DateResult(null, "invalid " + label + " \"" + s + "\" (not a real calendar date)");
    return new DateResult(iso, null);
  }

  /** ISO-8601 UTC with exactly three fraction digits (JavaScript's {@code Date.toISOString()}); sub-ms truncated. */
  public static String timestamp(OffsetDateTime t) {
    return t == null ? null : TIMESTAMP.format(t.toInstant().truncatedTo(ChronoUnit.MILLIS));
  }

  /** Today's date in UTC as YYYY-MM-DD. */
  public static String today(Clock clock) {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
  }
}
