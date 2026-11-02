package com.example.stoploss.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money is integer cents ({@code long}) everywhere inside the API and DB. These helpers convert at the edges only. No
 * floating point is ever used for amounts; {@link #dollars} produces the JSON number for responses.
 */
public final class Money {
  private Money() {}

  /** Largest amount accepted from a file: $999,999,999.99. Keeps every sum far inside the long range. */
  public static final long MAX_AMOUNT_CENTS = 99_999_999_999L;

  private static final Pattern PLAIN = Pattern.compile("(\\d+)(?:\\.(\\d{1,2}))?");
  private static final Pattern GROUPED = Pattern.compile("(\\d{1,3}(?:,\\d{3})+)(?:\\.(\\d{1,2}))?");

  /** Integer cents, or the reason the amount was rejected. */
  public record Amount(long cents, String error) {
    public boolean ok() {
      return error == null;
    }

    static Amount failed(String reason) {
      return new Amount(0, reason);
    }
  }

  /**
   * Parse a paid amount such as {@code 14,455.00}, {@code $4,532.16}, {@code $1343.78}, {@code 615.09} into integer
   * cents.
   *
   * <ul>
   *   <li>{@code $} and surrounding whitespace are allowed; at most 2 decimal places.
   *   <li>Thousands separators must be well-formed ({@code 1,234.56}); {@code 1234,56} (a decimal comma) is rejected
   *       rather than silently read as 123456.
   *   <li>Negative amounts ({@code -12.00}, {@code (12.00)}) are rejected: a reversal is signalled by the status column.
   *   <li>Blank, {@code N/A} and anything else that is not a number are rejected.
   * </ul>
   */
  public static Amount parseAmountToCents(String input) {
    String original = Js.trim(input);
    if (original.isEmpty()) return Amount.failed("paid amount is blank");

    String s = original;
    boolean negative = false;
    if (s.startsWith("(") && s.endsWith(")") && s.length() >= 2) {
      negative = true;
      s = Js.trim(s.substring(1, s.length() - 1));
    }
    if (s.startsWith("-")) {
      negative = true;
      s = Js.trim(s.substring(1));
    }
    if (s.startsWith("$")) s = Js.trim(s.substring(1));
    if (s.startsWith("-")) {
      negative = true;
      s = Js.trim(s.substring(1));
    }
    s = Js.removeSpaces(s);

    Matcher m = PLAIN.matcher(s);
    if (!m.matches()) {
      m = GROUPED.matcher(s);
      if (!m.matches()) return Amount.failed("invalid paid amount \"" + original + "\"");
    }
    if (negative) {
      return Amount.failed("negative paid amount \"" + original + "\" (reversals must use the status column)");
    }

    String whole = m.group(1).replace(",", "");
    String frac = m.group(2) == null ? "" : m.group(2);
    while (frac.length() < 2) frac = frac + "0";
    String significant = whole.replaceFirst("^0+", "");
    if (significant.length() > 9) return Amount.failed("paid amount \"" + original + "\" is out of range");
    long cents = (significant.isEmpty() ? 0 : Long.parseLong(significant)) * 100 + Long.parseLong(frac);
    if (cents > MAX_AMOUNT_CENTS) return Amount.failed("paid amount \"" + original + "\" is out of range");
    return new Amount(cents, null);
  }

  /**
   * Integer cents to dollars as a JSON number, written the way JavaScript prints {@code cents / 100}: 1800000 → 18000,
   * 47899 → 478.99, 47890 → 478.9, 0 → 0 (never 18000.00 or 1.8E+4).
   */
  public static BigDecimal dollars(long cents) {
    return plain(BigDecimal.valueOf(cents, 2));
  }

  /** {@code cents} as a human string for messages, e.g. 47899 → "478.99", 1445500 → "14,455.00". */
  public static String formatCents(long cents) {
    String sign = cents < 0 ? "-" : "";
    long abs = Math.abs(cents);
    return sign + String.format(Locale.US, "%,d", abs / 100) + "." + String.format(Locale.US, "%02d", abs % 100);
  }

  /**
   * Tenths of a percent of the deductible used, <strong>truncated</strong> (not rounded) so that a member at 49.99%
   * shows 49.9 and never "50.0" without a 50% alert.
   */
  public static long pctUsedTenths(long spendCents, long deductibleCents) {
    if (deductibleCents <= 0) throw new IllegalArgumentException("deductible must be positive");
    return Math.multiplyExact(spendCents, 1000L) / deductibleCents;
  }

  /** % used to 1 dp (truncated) as a JSON number: 49.9, 50, 112, 54.1. */
  public static BigDecimal pctUsed(long spendCents, long deductibleCents) {
    return plain(BigDecimal.valueOf(pctUsedTenths(spendCents, deductibleCents), 1));
  }

  public static long excessCents(long spendCents, long deductibleCents) {
    return Math.max(0, spendCents - deductibleCents);
  }

  /** Drop trailing zeros but never use exponent notation ({@code toString()} of the result is plain). */
  private static BigDecimal plain(BigDecimal d) {
    BigDecimal stripped = d.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
