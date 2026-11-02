package com.example.stoploss.domain;

import com.example.stoploss.domain.Layouts.Field;
import com.example.stoploss.domain.Layouts.Layout;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsing a whole claim file into claim rows, row errors and summary rows. Pure: no DB. */
public final class ClaimFile {
  private ClaimFile() {}

  public static final String PAID = "paid";
  public static final String REVERSED = "reversed";

  /**
   * One claim row after field parsing and normalisation. Dates are YYYY-MM-DD, money is integer cents, status is
   * {@code paid} or {@code reversed}; paid date and diagnosis code may be null.
   */
  public record ClaimRow(
      String claimId,
      int version,
      String memberId,
      String serviceDate,
      String paidDate,
      long amountCents,
      String status,
      String diagnosisCode) implements Versions.VersionValues {}

  /** One non-blank line after the header. */
  public sealed interface ParsedLine {
    int rowNumber();

    String raw();
  }

  public record ClaimLine(int rowNumber, String raw, ClaimRow claim) implements ParsedLine {}

  public record ErrorLine(int rowNumber, String raw, String reason) implements ParsedLine {}

  public record SummaryLine(int rowNumber, String raw) implements ParsedLine {}

  /** Either the layout and every parsed line, or the reason the whole file was rejected. */
  public record ParsedFile(Layout layout, int headerRowNumber, List<ParsedLine> lines, String error) {
    public boolean ok() {
      return error == null;
    }

    static ParsedFile failed(String reason) {
      return new ParsedFile(null, 0, List.of(), reason);
    }
  }

  /** Field value, or the reason it was rejected. */
  public record FieldResult<T>(T value, String error) {
    public boolean ok() {
      return error == null;
    }
  }

  private static final Set<String> PAID_CODES = Set.of("PAID", "P");
  private static final Set<String> REVERSED_CODES = Set.of("REVERSED", "R", "VOID", "VOIDED", "V");
  private static final Set<String> SUMMARY_MARKERS = Set.of("TRAILER", "TOTAL", "TOTALS", "SUMMARY", "GRAND TOTAL");
  private static final BigInteger MAX_VERSION = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final int MAX_ID_LENGTH = 64;

  public static FieldResult<String> parseStatus(String input) {
    String s = Js.upper(Js.trim(input));
    if (PAID_CODES.contains(s)) return new FieldResult<>(PAID, null);
    if (REVERSED_CODES.contains(s)) return new FieldResult<>(REVERSED, null);
    if (s.isEmpty()) return new FieldResult<>(null, "status is blank");
    return new FieldResult<>(null,
        "unknown status \"" + Js.trim(input) + "\" (expected PAID/P or REVERSED/R/VOID/V)");
  }

  public static FieldResult<Integer> parseVersion(String input) {
    String s = Js.trim(input);
    if (s.isEmpty()) return new FieldResult<>(null, "version is blank");
    if (!Js.isDigits(s)) return new FieldResult<>(null, "invalid version \"" + s + "\" (expected a whole number ≥ 0)");
    BigInteger v = new BigInteger(s);
    if (v.compareTo(MAX_VERSION) > 0) return new FieldResult<>(null, "version \"" + s + "\" is out of range");
    return new FieldResult<>(v.intValue(), null);
  }

  private static String summaryMarker(String cell) {
    String s = Js.trim(cell);
    if (s.endsWith(":")) s = s.substring(0, s.length() - 1);
    return Js.upper(s);
  }

  /** Is this row a summary/total row (e.g. the {@code TRAILER} line of TPA One files)? */
  public static boolean isSummaryRow(List<String> cells, Integer claimIdIndex) {
    String first = cells.isEmpty() ? "" : cells.getFirst();
    if (SUMMARY_MARKERS.contains(summaryMarker(first))) return true;
    if (claimIdIndex != null) {
      String c = claimIdIndex < cells.size() ? cells.get(claimIdIndex) : "";
      return SUMMARY_MARKERS.contains(summaryMarker(c));
    }
    return false;
  }

  /** An empty line, or one made only of separators/whitespace/empty quotes ({@code ,,,,,,,}). */
  static boolean isBlankRow(String line) {
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c != ',' && c != '"' && !Js.isSpace(c)) return false;
    }
    return true;
  }

  /** Parse the cells of one data row (already known not to be blank/summary) into a ClaimRow or a list of reasons. */
  public static FieldResult<ClaimRow> parseClaimCells(List<String> cells, Map<Field, Integer> indexes, int columnCount) {
    // Trailing empty cells beyond the header are tolerated (trailing commas); anything else means misaligned columns.
    int used = cells.size();
    while (used > columnCount && Js.trim(cells.get(used - 1)).isEmpty()) used--;
    if (used != columnCount) return new FieldResult<>(null, "Expected " + columnCount + " columns, got " + used);

    List<String> reasons = new ArrayList<>();

    String claimId = Js.trim(get(cells, indexes, Field.CLAIM_ID));
    if (claimId.isEmpty()) reasons.add("claim ID is blank");
    else if (claimId.length() > MAX_ID_LENGTH) reasons.add("claim ID is too long");

    FieldResult<Integer> version = parseVersion(get(cells, indexes, Field.VERSION));
    if (!version.ok()) reasons.add(version.error());

    String memberId = Js.trim(get(cells, indexes, Field.MEMBER_ID));
    if (memberId.isEmpty()) reasons.add("member ID is blank");
    else if (memberId.length() > MAX_ID_LENGTH) reasons.add("member ID is too long");

    Dates.DateResult serviceDate = Dates.parseDate(get(cells, indexes, Field.SERVICE_DATE), "service date");
    if (!serviceDate.ok()) reasons.add(serviceDate.error());

    String paidRaw = Js.trim(get(cells, indexes, Field.PAID_DATE));
    Dates.DateResult paidDate = paidRaw.isEmpty() ? null : Dates.parseDate(paidRaw, "paid date");
    if (paidDate != null && !paidDate.ok()) reasons.add(paidDate.error());

    Money.Amount amount = Money.parseAmountToCents(get(cells, indexes, Field.AMOUNT));
    if (!amount.ok()) reasons.add(amount.error());

    FieldResult<String> status = parseStatus(get(cells, indexes, Field.STATUS));
    if (!status.ok()) reasons.add(status.error());

    String diagnosis = Js.trim(get(cells, indexes, Field.DIAGNOSIS_CODE));

    if (!reasons.isEmpty()) return new FieldResult<>(null, Js.capitalise(String.join("; ", reasons)));
    return new FieldResult<>(
        new ClaimRow(
            claimId,
            version.value(),
            memberId,
            serviceDate.date(),
            paidDate == null ? null : paidDate.date(),
            amount.cents(),
            status.value(),
            diagnosis.isEmpty() ? null : diagnosis),
        null);
  }

  private static String get(List<String> cells, Map<Field, Integer> indexes, Field f) {
    Integer idx = indexes.get(f);
    return idx == null || idx >= cells.size() ? "" : cells.get(idx);
  }

  /** Parse file bytes (UTF-8, optional BOM). */
  public static ParsedFile parse(byte[] content) {
    return parseText(Csv.decodeText(content));
  }

  /** Parse already decoded text (one leading BOM is dropped). */
  public static ParsedFile parse(String content) {
    return parseText(Csv.decodeText(content));
  }

  /**
   * Rows are numbered by physical line (1-based, header and comment lines counted). Lines before the header that are
   * blank or start with {@code #} are skipped; blank rows are ignored.
   */
  private static ParsedFile parseText(String text) {
    List<String> lines = Csv.splitLines(text);

    int headerIndex = -1;
    for (int i = 0; i < lines.size(); i++) {
      String t = Js.trim(lines.get(i));
      if (t.isEmpty() || t.startsWith("#") || isBlankRow(t)) continue;
      headerIndex = i;
      break;
    }
    if (headerIndex == -1) return ParsedFile.failed("file has no header row");

    Csv.Line header = Csv.parseLine(lines.get(headerIndex));
    if (!header.ok()) return ParsedFile.failed("header row: " + header.error());
    Layouts.Match match = Layouts.detectLayout(header.cells());
    if (!match.ok()) return ParsedFile.failed(match.error());

    List<ParsedLine> out = new ArrayList<>();
    for (int i = headerIndex + 1; i < lines.size(); i++) {
      String raw = lines.get(i);
      int rowNumber = i + 1;
      if (isBlankRow(raw)) continue;
      Csv.Line parsed = Csv.parseLine(raw);
      if (!parsed.ok()) {
        out.add(new ErrorLine(rowNumber, raw, Js.capitalise(parsed.error())));
        continue;
      }
      if (isSummaryRow(parsed.cells(), match.indexes().get(Field.CLAIM_ID))) {
        out.add(new SummaryLine(rowNumber, raw));
        continue;
      }
      FieldResult<ClaimRow> row = parseClaimCells(parsed.cells(), match.indexes(), match.columnCount());
      out.add(row.ok() ? new ClaimLine(rowNumber, raw, row.value()) : new ErrorLine(rowNumber, raw, row.error()));
    }
    return new ParsedFile(match.layout(), headerIndex + 1, List.copyOf(out), null);
  }
}
