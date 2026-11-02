package com.example.stoploss.seed;

import com.example.stoploss.domain.Csv;
import com.example.stoploss.domain.Dates;
import com.example.stoploss.domain.Js;
import com.example.stoploss.domain.Layouts;
import com.example.stoploss.domain.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The reference CSVs in {@code DATA_DIR} (policies, members, lasers), parsed and validated. */
public record ReferenceData(
    List<Group> groups, List<PolicyYear> policyYears, List<Member> members, List<Laser> lasers) {

  public record Group(String id, String name) {}

  public record PolicyYear(String groupId, String startDate, String endDate, long deductibleCents) {}

  public record Member(String id, String groupId, String firstName, String lastName, String dateOfBirth,
      String relationship) {}

  public record Laser(String groupId, String memberId, String policyStart, long deductibleCents) {}

  /** Brokerages and their groups, seeded alongside the reference CSVs (docs/SPEC.md "Seed logins"). */
  public record SeedBrokerage(String id, String name, List<String> groups) {}

  public record SeedUser(String email, String role, String brokerageId) {}

  public static final List<SeedBrokerage> SEED_BROKERAGES = List.of(
      new SeedBrokerage("brokerage-a", "Brokerage A", List.of("GA")),
      new SeedBrokerage("brokerage-b", "Brokerage B", List.of("GB")));

  public static final List<SeedUser> SEED_USERS = List.of(
      new SeedUser("analyst@example.test", "analyst", null),
      new SeedUser("broker.a@example.test", "broker", "brokerage-a"),
      new SeedUser("broker.b@example.test", "broker", "brokerage-b"));

  /** One data row of a reference CSV; {@link #get} by (normalised) header name. Throws with file:line on bad input. */
  record Row(Path file, int line, List<String> columns, List<String> cells) {
    String get(String col) {
      int idx = columns.indexOf(Layouts.normaliseHeader(col));
      if (idx == -1) throw new IllegalStateException(file + ": missing column " + col);
      return Js.trim(idx < cells.size() ? cells.get(idx) : "");
    }

    String where() {
      return file + ":" + line;
    }
  }

  static List<Row> readCsv(Path file) {
    List<String> lines;
    try {
      lines = Csv.splitLines(Csv.decodeText(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    int first = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (!Js.trim(lines.get(i)).isEmpty()) {
        first = i;
        break;
      }
    }
    if (first == -1) throw new IllegalStateException(file + ": empty file");
    Csv.Line header = Csv.parseLine(lines.get(first));
    if (!header.ok()) throw new IllegalStateException(file + ":" + (first + 1) + ": " + header.error());
    List<String> cols = header.cells().stream().map(Layouts::normaliseHeader).toList();
    List<Row> out = new ArrayList<>();
    for (int i = first + 1; i < lines.size(); i++) {
      if (Js.trim(lines.get(i)).isEmpty()) continue;
      Csv.Line row = Csv.parseLine(lines.get(i));
      if (!row.ok()) throw new IllegalStateException(file + ":" + (i + 1) + ": " + row.error());
      out.add(new Row(file, i + 1, cols, row.cells()));
    }
    return out;
  }

  private static long dollarsToCents(String value, String where) {
    Money.Amount r = Money.parseAmountToCents(value);
    if (!r.ok()) throw new IllegalStateException(where + ": " + r.error());
    return r.cents();
  }

  private static String isoOrThrow(String value, String label, String where) {
    Dates.DateResult r = Dates.parseDate(value, label);
    if (!r.ok()) throw new IllegalStateException(where + ": " + r.error());
    return r.date();
  }

  public static ReferenceData load(Path dataDir) {
    Map<String, String> groups = new LinkedHashMap<>();
    List<PolicyYear> policyYears = new ArrayList<>();
    for (Row r : readCsv(dataDir.resolve("policies.csv"))) {
      String groupId = r.get("group_id");
      groups.put(groupId, r.get("group_name"));
      policyYears.add(new PolicyYear(
          groupId,
          isoOrThrow(r.get("policy_start"), "policy start", r.where()),
          isoOrThrow(r.get("policy_end"), "policy end", r.where()),
          dollarsToCents(r.get("specific_deductible"), r.where())));
    }

    List<Member> members = readCsv(dataDir.resolve("members.csv")).stream()
        .map(r -> {
          String dob = r.get("date_of_birth");
          String relationship = r.get("relationship");
          return new Member(
              r.get("member_id"),
              r.get("group_id"),
              r.get("first_name"),
              r.get("last_name"),
              dob.isEmpty() ? null : isoOrThrow(dob, "date of birth", r.where()),
              relationship.isEmpty() ? null : relationship);
        })
        .toList();

    List<Laser> lasers = readCsv(dataDir.resolve("lasers.csv")).stream()
        .map(r -> new Laser(
            r.get("group_id"),
            r.get("member_id"),
            isoOrThrow(r.get("policy_start"), "policy start", r.where()),
            dollarsToCents(r.get("specific_deductible"), r.where())))
        .toList();

    return new ReferenceData(
        groups.entrySet().stream().map(e -> new Group(e.getKey(), e.getValue())).toList(),
        List.copyOf(policyYears), members, lasers);
  }
}
