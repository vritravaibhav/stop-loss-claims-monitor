package com.example.stoploss.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Claim-file layouts. A layout is recognised from the header row (not from the group), columns are mapped by name (any
 * order, unknown extra columns ignored), and header matching ignores case, whitespace and punctuation ({@code Claim
 * ID}, {@code claim id}, {@code CLAIM_ID} all match).
 */
public final class Layouts {
  private Layouts() {}

  public enum Field {
    CLAIM_ID,
    VERSION,
    MEMBER_ID,
    SERVICE_DATE,
    PAID_DATE,
    AMOUNT,
    STATUS,
    DIAGNOSIS_CODE
  }

  /**
   * @param columns header name (as the TPA spells it) for each field, in header order
   * @param optionalColumns fields whose <em>column</em> may be missing (values of paid date / diagnosis may always be
   *     blank)
   */
  public record Layout(String id, String description, Map<Field, String> columns, Set<Field> optionalColumns) {}

  private static Map<Field, String> columns(Object... pairs) {
    Map<Field, String> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((Field) pairs[i], (String) pairs[i + 1]);
    return java.util.Collections.unmodifiableMap(m);
  }

  public static final Layout TPA_ONE = new Layout(
      "tpa_one",
      "TPA One (Group A): MM/DD/YYYY dates, PAID/REVERSED, Adj Seq from 0",
      columns(
          Field.CLAIM_ID, "Claim ID",
          Field.VERSION, "Adj Seq",
          Field.MEMBER_ID, "Member ID",
          Field.SERVICE_DATE, "Service Date",
          Field.PAID_DATE, "Paid Date",
          Field.AMOUNT, "Paid Amount",
          Field.STATUS, "Status",
          Field.DIAGNOSIS_CODE, "Diagnosis Code"),
      Set.of(Field.PAID_DATE, Field.DIAGNOSIS_CODE));

  public static final Layout TPA_TWO = new Layout(
      "tpa_two",
      "TPA Two (Group B): ISO dates, $ amounts, P/V status, version from 1",
      columns(
          Field.CLAIM_ID, "claim_number",
          Field.VERSION, "version",
          Field.MEMBER_ID, "member",
          Field.SERVICE_DATE, "dos",
          Field.PAID_DATE, "paid_dt",
          Field.AMOUNT, "amount",
          Field.STATUS, "claim_status"),
      Set.of(Field.PAID_DATE));

  public static final List<Layout> LAYOUTS = List.of(TPA_ONE, TPA_TWO);

  /** {@code Claim ID} → {@code claimid}, {@code claim_number} → {@code claimnumber}. */
  public static String normaliseHeader(String name) {
    String s = name.startsWith("﻿") ? name.substring(1) : name;
    s = Js.lower(s);
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
    }
    return out.toString();
  }

  /** The matched layout with the cell index of each field it has, or the reason the header was not recognised. */
  public record Match(Layout layout, Map<Field, Integer> indexes, int columnCount, String error) {
    public boolean ok() {
      return error == null;
    }

    static Match failed(String reason) {
      return new Match(null, Map.of(), 0, reason);
    }
  }

  public static Match detectLayout(List<String> headerCells) {
    return detectLayout(headerCells, LAYOUTS);
  }

  /** Match a header row (raw cells) against the known layouts. */
  public static Match detectLayout(List<String> headerCells, List<Layout> layouts) {
    List<String> normalised = headerCells.stream().map(Layouts::normaliseHeader).toList();
    // Trailing empty header cells (e.g. a trailing comma) are not columns.
    int columnCount = normalised.size();
    while (columnCount > 0 && normalised.get(columnCount - 1).isEmpty()) columnCount--;

    Map<String, Integer> seen = new HashMap<>();
    for (int i = 0; i < columnCount; i++) {
      String h = normalised.get(i);
      if (h.isEmpty()) continue;
      if (seen.containsKey(h)) {
        return Match.failed("duplicate column \"" + Js.trim(headerCells.get(i)) + "\" in header");
      }
      seen.put(h, i);
    }

    Layout bestLayout = null;
    List<String> bestMissing = null;
    int bestMatched = -1;
    for (Layout layout : layouts) {
      Map<Field, Integer> indexes = new EnumMap<>(Field.class);
      List<String> missing = new ArrayList<>();
      int matched = 0;
      for (Map.Entry<Field, String> e : layout.columns().entrySet()) {
        Integer idx = seen.get(normaliseHeader(e.getValue()));
        if (idx == null) {
          if (!layout.optionalColumns().contains(e.getKey())) missing.add(e.getValue());
        } else {
          indexes.put(e.getKey(), idx);
          matched++;
        }
      }
      if (missing.isEmpty()) return new Match(layout, indexes, columnCount, null);
      if (bestLayout == null || matched > bestMatched) {
        bestLayout = layout;
        bestMissing = missing;
        bestMatched = matched;
      }
    }

    if (bestLayout != null && bestMatched >= 2) {
      return Match.failed("header looks like layout " + bestLayout.id() + " but is missing column(s): "
          + String.join(", ", bestMissing));
    }
    String expected = layouts.stream()
        .map(l -> l.id() + " (" + String.join(", ", l.columns().values()) + ")")
        .collect(Collectors.joining(" or "));
    return Match.failed("unrecognised header; expected the columns of " + expected);
  }
}
