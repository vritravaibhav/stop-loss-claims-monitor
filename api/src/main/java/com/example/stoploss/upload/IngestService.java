package com.example.stoploss.upload;

import com.example.stoploss.domain.Alerts;
import com.example.stoploss.domain.ClaimFile;
import com.example.stoploss.domain.ClaimFile.ClaimLine;
import com.example.stoploss.domain.ClaimFile.ClaimRow;
import com.example.stoploss.domain.ClaimFile.ErrorLine;
import com.example.stoploss.domain.ClaimFile.ParsedFile;
import com.example.stoploss.domain.ClaimFile.ParsedLine;
import com.example.stoploss.domain.ClaimFile.SummaryLine;
import com.example.stoploss.domain.Dates;
import com.example.stoploss.domain.Js;
import com.example.stoploss.domain.Money;
import com.example.stoploss.domain.Versions;
import com.example.stoploss.domain.Versions.ClaimHistory;
import com.example.stoploss.domain.Versions.Decision;
import com.example.stoploss.domain.Versions.StoredVersion;
import com.example.stoploss.upload.UploadResult.AlertRaised;
import com.example.stoploss.upload.UploadResult.Counts;
import com.example.stoploss.upload.UploadResult.RowError;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Load one claim file. Everything for the file happens in one transaction that holds an advisory lock on the group, so
 * concurrent uploads for the same group serialise. Steps:
 *
 * <ol>
 *   <li>parse (pure) → claim rows / row errors / summary rows;
 *   <li>validate members, decide each row against the stored versions (in file order, so duplicates inside the file
 *       are handled the same as across files);
 *   <li>insert the new versions (current or older) and recompute {@code claim_current} for claims that got a newer
 *       version;
 *   <li>for every (member, policy year) touched (the new placement <em>and</em> the old one if a newer version moved the
 *       claim) compute spend and raise alerts for thresholds reached for the first time.
 * </ol>
 *
 * Rejected files (unknown group prefix, unrecognised header) are recorded with status {@code rejected}.
 */
@Service
public class IngestService {
  private static final Pattern GROUP_PREFIX = Pattern.compile("([A-Za-z0-9-]+)_");

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public IngestService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** Strip any client-side path ({@code C:\fakepath\GA_2026-04.csv}, {@code dir/GA.csv}) and keep the bare name. */
  public static String baseFilename(String name) {
    String[] parts = (name == null ? "" : name).split("[\\\\/]", -1);
    String last = Js.trim(parts[parts.length - 1]);
    return last.isEmpty() ? "unnamed.csv" : last;
  }

  /** Group id = file-name prefix before the first {@code _} ({@code GA_2026-04_resend.csv} → {@code GA}). */
  public static String groupPrefixFromFilename(String filename) {
    Matcher m = GROUP_PREFIX.matcher(filename);
    return m.lookingAt() ? m.group(1) : null;
  }

  public UploadResult ingest(String originalFilename, byte[] content, String uploadedBy) {
    String filename = baseFilename(originalFilename);
    String sha256 = sha256(content);
    ParsedFile parsed = ClaimFile.parse(content);
    String prefix = groupPrefixFromFilename(filename);
    return tx.execute(status -> ingestInTransaction(filename, sha256, content.length, parsed, prefix, uploadedBy));
  }

  private UploadResult ingestInTransaction(String filename, String sha256, int sizeBytes, ParsedFile parsed,
      String prefix, String uploadedBy) {
    String groupId = null;
    if (prefix != null) {
      List<String> g = jdbc.queryForList("SELECT id FROM employer_group WHERE upper(id) = upper(:prefix)",
          Map.of("prefix", prefix), String.class);
      groupId = g.isEmpty() ? null : g.getFirst();
    }

    if (groupId == null) {
      String reason = prefix != null
          ? "Unknown group \"" + prefix + "\" (taken from the file name prefix before the first \"_\")"
          : "File name must start with the group ID followed by \"_\" (for example GA_2026-04.csv)";
      return insertRejected(filename, sha256, sizeBytes, uploadedBy, null, reason);
    }

    // Serialise every upload for this group (released automatically at COMMIT/ROLLBACK).
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext('stoploss.ingest'), hashtext(:groupId))",
        Map.of("groupId", groupId), rs -> null);

    if (!parsed.ok()) {
      return insertRejected(filename, sha256, sizeBytes, uploadedBy, groupId, Js.capitalise(parsed.error()));
    }

    record Inserted(long id, OffsetDateTime uploadedAt) {}
    Inserted up = jdbc.queryForObject(
        """
        INSERT INTO upload (group_id, filename, sha256, size_bytes, layout, status, uploaded_by)
        VALUES (:groupId, :filename, :sha256, :sizeBytes, :layout, 'processed', :uploadedBy) RETURNING id, uploaded_at""",
        new MapSqlParameterSource()
            .addValue("groupId", groupId)
            .addValue("filename", filename)
            .addValue("sha256", sha256)
            .addValue("sizeBytes", sizeBytes)
            .addValue("layout", parsed.layout().id())
            .addValue("uploadedBy", uploadedBy),
        (rs, i) -> new Inserted(rs.getLong("id"), rs.getObject("uploaded_at", OffsetDateTime.class)));
    long uploadId = up.id();

    List<ClaimLine> claimLines = new ArrayList<>();
    for (ParsedLine l : parsed.lines()) if (l instanceof ClaimLine c) claimLines.add(c);

    // Members referenced by the file → their group (unknown ids are simply absent).
    Map<String, String> memberGroup = new HashMap<>();
    Set<String> memberIds = new LinkedHashSet<>();
    for (ClaimLine l : claimLines) memberIds.add(l.claim().memberId());
    if (!memberIds.isEmpty()) {
      jdbc.query("SELECT id, group_id FROM member WHERE id = ANY(CAST(:ids AS text[]))",
          Map.of("ids", textArray(memberIds)),
          rs -> {
            memberGroup.put(rs.getString("id"), rs.getString("group_id"));
          });
    }

    // Everything already stored for the claims in this file.
    Map<String, ClaimHistory> histories = new HashMap<>();
    Set<String> claimIds = new LinkedHashSet<>();
    for (ClaimLine l : claimLines) claimIds.add(l.claim().claimId());
    if (!claimIds.isEmpty()) {
      jdbc.query(
          """
          SELECT cv.claim_id, cv.version, cv.member_id, cv.service_date, cv.paid_date, cv.paid_amount_cents, cv.status,
                 cv.diagnosis_code, cv.row_number, u.filename
          FROM claim_version cv JOIN upload u ON u.id = cv.upload_id
          WHERE cv.group_id = :groupId AND cv.claim_id = ANY(CAST(:claimIds AS text[]))""",
          Map.of("groupId", groupId, "claimIds", textArray(claimIds)),
          rs -> {
            Versions.recordVersion(histories, rs.getString("claim_id"), new StoredVersion(
                rs.getInt("version"),
                rs.getString("member_id"),
                rs.getString("service_date"),
                rs.getString("paid_date"),
                rs.getLong("paid_amount_cents"),
                rs.getString("status"),
                rs.getString("diagnosis_code"),
                rs.getString("filename"),
                rs.getInt("row_number")));
          });
    }

    // Decide every row in file order.
    int rows = 0;
    int applied = 0;
    int unchanged = 0;
    int olderIgnored = 0;
    int summary = 0;
    List<RowError> errors = new ArrayList<>();
    List<ClaimLine> toInsert = new ArrayList<>();
    Set<String> newerClaims = new LinkedHashSet<>();

    for (ParsedLine line : parsed.lines()) {
      rows++;
      switch (line) {
        case SummaryLine s -> summary++;
        case ErrorLine e -> errors.add(new RowError(e.rowNumber(), e.reason(), e.raw()));
        case ClaimLine l -> {
          ClaimRow c = l.claim();
          String owner = memberGroup.get(c.memberId());
          if (owner == null) {
            errors.add(new RowError(l.rowNumber(), "Unknown member \"" + c.memberId() + "\"", l.raw()));
            continue;
          }
          if (!owner.equals(groupId)) {
            errors.add(new RowError(l.rowNumber(),
                "Member \"" + c.memberId() + "\" belongs to group " + owner + ", not " + groupId, l.raw()));
            continue;
          }

          ClaimHistory history = histories.get(c.claimId());
          Decision decision = Versions.decideVersion(history, c.version(), c);
          StoredVersion stored = StoredVersion.of(c.version(), c, filename, l.rowNumber());
          switch (decision.outcome()) {
            case NEW -> {
              applied++;
              toInsert.add(l);
              Versions.recordVersion(histories, c.claimId(), stored);
              newerClaims.add(c.claimId());
            }
            case OLDER_IGNORED -> {
              olderIgnored++;
              toInsert.add(l);
              Versions.recordVersion(histories, c.claimId(), stored);
            }
            case UNCHANGED -> unchanged++;
            case CONFLICT -> errors.add(new RowError(l.rowNumber(),
                Versions.conflictReason(c.claimId(), c.version(), history.versions().get(c.version()),
                    decision.differences()),
                l.raw()));
          }
        }
      }
    }
    Counts counts = new Counts(rows, applied, unchanged, olderIgnored, summary, errors.size());

    if (!toInsert.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO claim_version (group_id, claim_id, version, member_id, service_date, paid_date, paid_amount_cents,
                                     status, diagnosis_code, upload_id, row_number)
          SELECT :groupId, t.claim_id, t.version, t.member_id, t.service_date, t.paid_date, t.amount, t.status,
                 t.diagnosis, :uploadId, t.row_number
          FROM unnest(CAST(:claimIds AS text[]), CAST(:versions AS int[]), CAST(:memberIds AS text[]),
                      CAST(:serviceDates AS date[]), CAST(:paidDates AS date[]), CAST(:amounts AS bigint[]),
                      CAST(:statuses AS text[]), CAST(:diagnoses AS text[]), CAST(:rowNumbers AS int[]))
            AS t(claim_id, version, member_id, service_date, paid_date, amount, status, diagnosis, row_number)""",
          new MapSqlParameterSource()
              .addValue("groupId", groupId)
              .addValue("uploadId", uploadId)
              .addValue("claimIds", textArray(toInsert.stream().map(l -> l.claim().claimId()).toList()))
              .addValue("versions", intArray(toInsert.stream().map(l -> l.claim().version()).toList()))
              .addValue("memberIds", textArray(toInsert.stream().map(l -> l.claim().memberId()).toList()))
              .addValue("serviceDates", textArray(toInsert.stream().map(l -> l.claim().serviceDate()).toList()))
              .addValue("paidDates", textArray(toInsert.stream().map(l -> l.claim().paidDate()).toList()))
              .addValue("amounts", bigintArray(toInsert.stream().map(l -> l.claim().amountCents()).toList()))
              .addValue("statuses", textArray(toInsert.stream().map(l -> l.claim().status()).toList()))
              .addValue("diagnoses", textArray(toInsert.stream().map(l -> l.claim().diagnosisCode()).toList()))
              .addValue("rowNumbers", intArray(toInsert.stream().map(ClaimLine::rowNumber).toList())));
    }

    // (member, policy year) pairs whose spend may have changed, in first-touched order.
    record Pair(String memberId, int policyYearId) {}
    Map<String, Pair> touched = new LinkedHashMap<>();
    org.springframework.jdbc.core.RowCallbackHandler touch = rs -> {
      int py = rs.getInt("policy_year_id");
      if (!rs.wasNull()) {
        String memberId = rs.getString("member_id");
        touched.put(memberId + "|" + py, new Pair(memberId, py));
      }
    };

    if (!newerClaims.isEmpty()) {
      SqlArrayValue changed = textArray(newerClaims);
      // Where these claims counted before this file (a newer version may move a claim to another member or year).
      jdbc.query(
          "SELECT member_id, policy_year_id FROM claim_current WHERE group_id = :groupId AND claim_id = ANY(CAST(:changed AS text[]))",
          Map.of("groupId", groupId, "changed", changed), touch);

      // Current version = highest stored version; policy year by service date; reversed → 0.
      jdbc.query(
          """
          INSERT INTO claim_current (group_id, claim_id, claim_version_id, version, member_id, service_date,
                                     policy_year_id, effective_amount_cents)
          SELECT DISTINCT ON (cv.claim_id)
                 cv.group_id, cv.claim_id, cv.id, cv.version, cv.member_id, cv.service_date, py.id,
                 CASE WHEN cv.status = 'reversed' THEN 0 ELSE cv.paid_amount_cents END
          FROM claim_version cv
          LEFT JOIN policy_year py
            ON py.group_id = cv.group_id AND cv.service_date BETWEEN py.start_date AND py.end_date
          WHERE cv.group_id = :groupId AND cv.claim_id = ANY(CAST(:changed AS text[]))
          ORDER BY cv.claim_id, cv.version DESC
          ON CONFLICT (group_id, claim_id) DO UPDATE SET
            claim_version_id = EXCLUDED.claim_version_id,
            version = EXCLUDED.version,
            member_id = EXCLUDED.member_id,
            service_date = EXCLUDED.service_date,
            policy_year_id = EXCLUDED.policy_year_id,
            effective_amount_cents = EXCLUDED.effective_amount_cents
          RETURNING member_id, policy_year_id""",
          Map.of("groupId", groupId, "changed", textArray(newerClaims)), touch);
    }

    List<AlertRaised> alertsRaised = new ArrayList<>();
    if (!touched.isEmpty()) {
      List<Pair> pairs = List.copyOf(touched.values());
      record Candidate(String memberId, int policyYearId, int threshold, long spend, long ded) {}
      List<Candidate> candidates = new ArrayList<>();
      jdbc.query(
          """
          SELECT p.member_id, p.policy_year_id,
                 COALESCE((SELECT sum(cc.effective_amount_cents) FROM claim_current cc
                           WHERE cc.member_id = p.member_id AND cc.policy_year_id = p.policy_year_id), 0)::bigint
                   AS spend_cents,
                 COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS deductible_cents,
                 ARRAY(SELECT a.threshold FROM alert a
                       WHERE a.member_id = p.member_id AND a.policy_year_id = p.policy_year_id) AS alerted
          FROM unnest(CAST(:memberIds AS text[]), CAST(:policyYearIds AS int[])) AS p(member_id, policy_year_id)
          JOIN policy_year py ON py.id = p.policy_year_id
          LEFT JOIN laser l ON l.member_id = p.member_id AND l.policy_year_id = p.policy_year_id""",
          Map.of(
              "memberIds", textArray(pairs.stream().map(Pair::memberId).toList()),
              "policyYearIds", intArray(pairs.stream().map(Pair::policyYearId).toList())),
          rs -> {
            long spend = rs.getLong("spend_cents");
            long ded = rs.getLong("deductible_cents");
            Set<Integer> alerted = new HashSet<>(Arrays.asList((Integer[]) rs.getArray("alerted").getArray()));
            for (int t : Alerts.newAlertThresholds(spend, ded, alerted)) {
              candidates.add(new Candidate(rs.getString("member_id"), rs.getInt("policy_year_id"), t, spend, ded));
            }
          });

      if (!candidates.isEmpty()) {
        // The unique (member, policy year, threshold) constraint is the real guard against duplicates.
        List<AlertRaised> inserted = jdbc.query(
            """
            INSERT INTO alert (group_id, member_id, policy_year_id, threshold, spend_cents_at_trigger,
                               deductible_cents_at_trigger, upload_id)
            SELECT :groupId, c.member_id, c.policy_year_id, c.threshold, c.spend, c.ded, :uploadId
            FROM unnest(CAST(:memberIds AS text[]), CAST(:policyYearIds AS int[]), CAST(:thresholds AS int[]),
                        CAST(:spends AS bigint[]), CAST(:deds AS bigint[]))
              AS c(member_id, policy_year_id, threshold, spend, ded)
            ON CONFLICT (member_id, policy_year_id, threshold) DO NOTHING
            RETURNING id, member_id, threshold, spend_cents_at_trigger, deductible_cents_at_trigger""",
            new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("uploadId", uploadId)
                .addValue("memberIds", textArray(candidates.stream().map(Candidate::memberId).toList()))
                .addValue("policyYearIds", intArray(candidates.stream().map(Candidate::policyYearId).toList()))
                .addValue("thresholds", intArray(candidates.stream().map(Candidate::threshold).toList()))
                .addValue("spends", bigintArray(candidates.stream().map(Candidate::spend).toList()))
                .addValue("deds", bigintArray(candidates.stream().map(Candidate::ded).toList())),
            (rs, i) -> alertRaised(rs.getLong("id"), rs.getString("member_id"), rs.getInt("threshold"),
                rs.getLong("spend_cents_at_trigger"), rs.getLong("deductible_cents_at_trigger")));
        alertsRaised.addAll(inserted);
        alertsRaised.sort(Comparator.comparing(AlertRaised::memberId).thenComparingInt(AlertRaised::threshold));
      }
    }

    if (!errors.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO upload_row_error (upload_id, row_number, reason, raw)
          SELECT :uploadId, * FROM unnest(CAST(:rowNumbers AS int[]), CAST(:reasons AS text[]), CAST(:raws AS text[]))""",
          new MapSqlParameterSource()
              .addValue("uploadId", uploadId)
              .addValue("rowNumbers", intArray(errors.stream().map(RowError::rowNumber).toList()))
              .addValue("reasons", textArray(errors.stream().map(RowError::reason).toList()))
              .addValue("raws", textArray(errors.stream().map(RowError::raw).toList())));
    }

    jdbc.update(
        """
        UPDATE upload SET row_count = :rows, applied_count = :applied, unchanged_count = :unchanged,
                          older_ignored_count = :olderIgnored, summary_count = :summary, error_count = :errors
        WHERE id = :uploadId""",
        new MapSqlParameterSource()
            .addValue("uploadId", uploadId)
            .addValue("rows", counts.rows())
            .addValue("applied", counts.applied())
            .addValue("unchanged", counts.unchanged())
            .addValue("olderIgnored", counts.olderIgnored())
            .addValue("summary", counts.summary())
            .addValue("errors", counts.errors()));

    return new UploadResult(uploadId, filename, groupId, parsed.layout().id(), UploadResult.PROCESSED, null, uploadedBy,
        Dates.timestamp(up.uploadedAt()), sha256, counts, List.copyOf(errors), List.copyOf(alertsRaised));
  }

  private UploadResult insertRejected(String filename, String sha256, int sizeBytes, String uploadedBy, String groupId,
      String reason) {
    record Inserted(long id, OffsetDateTime uploadedAt) {}
    Inserted r = jdbc.queryForObject(
        """
        INSERT INTO upload (group_id, filename, sha256, size_bytes, layout, status, rejection_reason, uploaded_by)
        VALUES (:groupId, :filename, :sha256, :sizeBytes, NULL, 'rejected', :reason, :uploadedBy)
        RETURNING id, uploaded_at""",
        new MapSqlParameterSource()
            .addValue("groupId", groupId, Types.VARCHAR)
            .addValue("filename", filename)
            .addValue("sha256", sha256)
            .addValue("sizeBytes", sizeBytes)
            .addValue("reason", reason)
            .addValue("uploadedBy", uploadedBy),
        (rs, i) -> new Inserted(rs.getLong("id"), rs.getObject("uploaded_at", OffsetDateTime.class)));
    return new UploadResult(r.id(), filename, groupId, null, UploadResult.REJECTED, reason, uploadedBy,
        Dates.timestamp(r.uploadedAt()), sha256, Counts.EMPTY, List.of(), List.of());
  }

  static AlertRaised alertRaised(long id, String memberId, int threshold, long spendCents, long deductibleCents) {
    return new AlertRaised(id, memberId, threshold, Money.dollars(spendCents), Money.dollars(deductibleCents),
        Money.pctUsed(spendCents, deductibleCents));
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static SqlArrayValue textArray(java.util.Collection<String> values) {
    return new SqlArrayValue("text", values.toArray(new String[0]));
  }

  static SqlArrayValue intArray(List<Integer> values) {
    return new SqlArrayValue("int4", values.toArray(new Integer[0]));
  }

  static SqlArrayValue bigintArray(List<Long> values) {
    return new SqlArrayValue("int8", values.toArray(new Long[0]));
  }
}
