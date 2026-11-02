package com.example.stoploss.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.stoploss.TestData;
import com.example.stoploss.seed.LoadSampleService;
import com.example.stoploss.support.DbTest;
import com.example.stoploss.upload.UploadResult.Counts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Golden test: load every sample file in file-name order (exactly like {@code --app.mode=load-sample} and the analyst's
 * multi-file upload) and check the known traps. The expected values are the Node API's golden test, which were
 * cross-checked against an independent re-implementation of the rules ({@code tools/reference_model.py}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenTest extends DbTest {
  private final Map<String, UploadResult> results = new LinkedHashMap<>();
  private final Map<String, Long> spendBeforeResend = new LinkedHashMap<>();
  private final Map<String, Long> spendAfterResend = new LinkedHashMap<>();

  @BeforeAll
  void loadAllSampleFiles() throws Exception {
    ensureSeeded();
    resetClaims();
    for (String f : LoadSampleService.listClaimFiles(TestData.CLAIMS_DIR)) {
      if (f.equals("GA_2026-04_resend.csv")) spendBeforeResend.putAll(allSpend());
      results.put(f, ingest.ingest(f, TestData.sample(f), "golden"));
      if (f.equals("GA_2026-04_resend.csv")) spendAfterResend.putAll(allSpend());
    }
  }

  UploadResult res(String f) {
    UploadResult r = results.get(f);
    assertThat(r).as(f + " was loaded").isNotNull();
    return r;
  }

  List<String> alertKeys(String f) {
    return res(f).alertsRaised().stream().map(a -> a.memberId() + "@" + a.threshold()).toList();
  }

  @Test
  void loadsFilesInByteOrderSoGa202604ComesBeforeTheResend() {
    List<String> order = new ArrayList<>(results.keySet());
    assertThat(order.indexOf("GA_2026-04.csv")).isEqualTo(order.indexOf("GA_2026-04_resend.csv") - 1);
    assertThat(order).hasSize(16);
  }

  @Test
  void perFileCounts() {
    Map<String, Counts> counts = new LinkedHashMap<>();
    results.forEach((f, r) -> counts.put(f, r.counts()));
    Map<String, Counts> expected = new LinkedHashMap<>();
    expected.put("GA_2026-01.csv", new Counts(222, 221, 0, 0, 1, 0));
    expected.put("GA_2026-02.csv", new Counts(224, 222, 0, 0, 1, 1));
    expected.put("GA_2026-03.csv", new Counts(230, 228, 0, 0, 1, 1));
    expected.put("GA_2026-04.csv", new Counts(227, 226, 0, 0, 1, 0));
    expected.put("GA_2026-04_resend.csv", new Counts(227, 0, 226, 0, 1, 0));
    expected.put("GA_2026-05.csv", new Counts(243, 241, 0, 0, 1, 1));
    expected.put("GA_2026-06.csv", new Counts(243, 241, 0, 1, 1, 0));
    expected.put("GA_2026-07.csv", new Counts(247, 245, 1, 0, 1, 0));
    expected.put("GA_2026-08.csv", new Counts(252, 250, 0, 0, 1, 1));
    expected.put("GA_2026-09.csv", new Counts(269, 268, 0, 0, 1, 0));
    expected.put("GB_2026-04.csv", new Counts(181, 181, 0, 0, 0, 0));
    expected.put("GB_2026-05.csv", new Counts(183, 183, 0, 0, 0, 0));
    expected.put("GB_2026-06.csv", new Counts(188, 188, 0, 0, 0, 0));
    expected.put("GB_2026-07.csv", new Counts(193, 193, 0, 0, 0, 0));
    expected.put("GB_2026-08.csv", new Counts(200, 200, 0, 0, 0, 0));
    expected.put("GB_2026-09.csv", new Counts(218, 218, 0, 0, 0, 0));
    assertThat(counts).containsExactlyEntriesOf(expected);
    for (UploadResult r : results.values()) {
      assertThat(r.status()).isEqualTo("processed");
      assertThat(r.layout()).isEqualTo(r.groupId().equals("GA") ? "tpa_one" : "tpa_two");
    }
  }

  @Test
  void everyBadRowWithItsPhysicalLineNumberAndReason() {
    List<Object> errors = new ArrayList<>();
    results.forEach((f, r) -> r.errors().forEach(e -> errors.add(tuple(f, e.rowNumber(), e.reason()))));
    assertThat(errors).containsExactly(
        tuple("GA_2026-02.csv", 45, "Unknown member \"A-0999\""),
        tuple("GA_2026-03.csv", 140, "Member \"B-0004\" belongs to group GB, not GA"),
        tuple("GA_2026-05.csv", 18, "Invalid paid amount \"N/A\""),
        tuple("GA_2026-08.csv", 7,
            "Conflict: claim 101410 version 0 was already loaded with different values (first loaded from"
                + " GA_2026-07.csv row 22): paid amount 478.99 → 578.99. The first-loaded values were kept"));
    assertThat(res("GA_2026-02.csv").errors().getFirst().raw())
        .isEqualTo("101993,0,A-0999,02/02/2026,02/12/2026,800.00,PAID,C50.911");
  }

  @Test
  void ga202601TrailerIsASummaryRowAndOutOfPolicyYearServiceDatesAreStoredButCountNowhere() {
    assertThat(res("GA_2026-01.csv").counts().summary()).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM claim_current WHERE group_id = 'GA' AND policy_year_id IS NULL", Long.class))
        .isPositive();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM claim_current WHERE group_id = 'GA' AND service_date < '2026-01-01' AND policy_year_id IS NOT NULL",
        Long.class)).isZero();
  }

  @Test
  void claim101991OfA0013IsEffective18000AfterTheLateV1IsIgnored() {
    // v0 9,000 → v2 18,000 → late v1 25,000 is older_ignored; effective 18,000
    assertThat(jdbc.queryForList(
        "SELECT version, effective_amount_cents FROM claim_current WHERE group_id = 'GA' AND claim_id = '101991'"))
        .containsExactly(Map.of("version", 2, "effective_amount_cents", 1_800_000L));
    assertThat(jdbc.queryForList(
        """
        SELECT cv.version, cv.paid_amount_cents, u.filename, cv.row_number
        FROM claim_version cv JOIN upload u ON u.id = cv.upload_id
        WHERE cv.group_id = 'GA' AND cv.claim_id = '101991' ORDER BY cv.version"""))
        .containsExactly(
            Map.of("version", 0, "paid_amount_cents", 900_000L, "filename", "GA_2026-03.csv", "row_number", 174),
            Map.of("version", 1, "paid_amount_cents", 2_500_000L, "filename", "GA_2026-06.csv", "row_number", 85),
            Map.of("version", 2, "paid_amount_cents", 1_800_000L, "filename", "GA_2026-05.csv", "row_number", 9));
    assertThat(res("GA_2026-06.csv").counts().olderIgnored()).isEqualTo(1);
  }

  @Test
  void claim101988OfA0009ReversalMakesItZeroAndTheSingle50AlertStays() {
    assertThat(alertKeys("GA_2026-03.csv")).contains("A-0009@50");
    assertThat(jdbc.queryForList(
        "SELECT version, effective_amount_cents FROM claim_current WHERE group_id = 'GA' AND claim_id = '101988'"))
        .containsExactly(Map.of("version", 1, "effective_amount_cents", 0L));
    List<AlertRow> alerts = alertsFor("A-0009");
    assertThat(alerts).extracting(AlertRow::threshold, AlertRow::status).containsExactly(tuple(50, "open"));
    assertThat(alerts.getFirst().spendCentsAtTrigger()).isGreaterThanOrEqualTo(3_000_000);
    long files = results.values().stream()
        .filter(r -> r.alertsRaised().stream().anyMatch(a -> a.memberId().equals("A-0009")))
        .count();
    assertThat(files).isEqualTo(1);
  }

  @Test
  void ga202604ResendByteIdenticalToGa202604ChangesNothing() {
    UploadResult resend = res("GA_2026-04_resend.csv");
    assertThat(resend.sha256()).isEqualTo(res("GA_2026-04.csv").sha256());
    assertThat(resend.counts().unchanged()).isEqualTo(226);
    assertThat(resend.alertsRaised()).isEmpty();
    assertThat(spendBeforeResend).isNotEmpty();
    assertThat(spendAfterResend).isEqualTo(spendBeforeResend);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_version WHERE upload_id = ?", Long.class, resend.id()))
        .isZero();
  }

  @Test
  void ga202607IdenticalDuplicateOfClaim101394V0InOneFileIsUnchanged() {
    // lines 12 and 124
    assertThat(res("GA_2026-07.csv").counts().unchanged()).isEqualTo(1);
    assertThat(jdbc.queryForList(
        "SELECT row_number FROM claim_version WHERE group_id = 'GA' AND claim_id = '101394' AND version = 0",
        Integer.class)).containsExactly(12);
  }

  @Test
  void ga202608Line7ConflictingClaim101410V0KeepsTheFirstLoaded47899() {
    assertThat(jdbc.queryForList(
        "SELECT effective_amount_cents FROM claim_current WHERE group_id = 'GA' AND claim_id = '101410'", Long.class))
        .containsExactly(47_899L);
  }

  @Test
  void a0007IsMeasuredAgainstThe120000LaserNot50000() {
    assertThat(spendCents("A-0007", "2026-06-01")).isEqualTo(6_500_000);
    assertThat(alertsFor("A-0007")).extracting(AlertRow::threshold, AlertRow::deductibleCentsAtTrigger)
        .containsExactly(tuple(50, 12_000_000L));
  }

  @Test
  void alertsRaisedPerFile() {
    Map<String, List<String>> perFile = new LinkedHashMap<>();
    results.keySet().forEach(f -> perFile.put(f, alertKeys(f)));
    Map<String, List<String>> expected = new LinkedHashMap<>();
    expected.put("GA_2026-01.csv", List.of());
    expected.put("GA_2026-02.csv", List.of());
    expected.put("GA_2026-03.csv", List.of("A-0003@50", "A-0009@50"));
    expected.put("GA_2026-04.csv", List.of("A-0011@50", "A-0011@75", "A-0011@100"));
    expected.put("GA_2026-04_resend.csv", List.of());
    expected.put("GA_2026-05.csv", List.of("A-0003@75"));
    expected.put("GA_2026-06.csv", List.of("A-0005@50", "A-0005@75", "A-0005@100", "A-0007@50", "A-0071@50", "A-0162@50"));
    expected.put("GA_2026-07.csv", List.of("A-0003@100", "A-0077@50", "A-0200@50"));
    expected.put("GA_2026-08.csv", List.of());
    expected.put("GA_2026-09.csv", List.of("A-0077@75"));
    expected.put("GB_2026-04.csv", List.of());
    expected.put("GB_2026-05.csv", List.of("B-0002@50", "B-0002@75"));
    expected.put("GB_2026-06.csv", List.of());
    expected.put("GB_2026-07.csv", List.of("B-0006@50", "B-0006@75"));
    expected.put("GB_2026-08.csv", List.of("B-0008@50", "B-0012@50"));
    expected.put("GB_2026-09.csv", List.of("B-0010@50"));
    assertThat(perFile).containsExactlyEntriesOf(expected);
  }

  @Test
  void alertsTotalByThresholdAtMostOnePerMemberPolicyYearThreshold() {
    assertThat(jdbc.queryForList("SELECT threshold, count(*) AS n FROM alert GROUP BY threshold ORDER BY threshold"))
        .containsExactly(
            Map.of("threshold", 50, "n", 14L),
            Map.of("threshold", 75, "n", 6L),
            Map.of("threshold", 100, "n", 3L));
  }

  @Test
  void a0011Gets50And75And100InOneFileThenV1LowersSpendTo74PercentAndTheAlertsStay() {
    assertThat(spendCents("A-0011", "2026-04-02")).isEqualTo(3_700_000);
    assertThat(alertsFor("A-0011")).extracting(AlertRow::threshold).containsExactly(50, 75, 100);
  }

  @Test
  void gbClaimsCountByServiceDateB0006IsIn2025To26() {
    // $45,000 with DOS 2026-06-30 paid in July
    assertThat(spendCents("B-0006", "2026-06-30")).isEqualTo(6_000_000);
    assertThat(alertsFor("B-0006")).extracting(AlertRow::startDate, AlertRow::threshold)
        .containsExactly(tuple("2025-07-01", 50), tuple("2025-07-01", 75));
  }

  @Test
  void gbClaim7701086MovedFrom2025To26Into2026To27BothYearsRecomputed() {
    assertThat(spendCents("B-0008", "2026-06-30")).isZero();
    assertThat(spendCents("B-0008", "2026-07-01")).isEqualTo(4_500_000);
    assertThat(alertsFor("B-0008")).extracting(AlertRow::startDate, AlertRow::threshold)
        .containsExactly(tuple("2026-07-01", 50));
  }

  @Test
  void gbB0012Gets50InGb202608VoidedInGb202609SpendZeroAlertRemains() {
    assertThat(spendCents("B-0012", "2026-08-04")).isZero();
    assertThat(alertsFor("B-0012")).extracting(AlertRow::threshold).containsExactly(50);
  }

  @Test
  void topMembersByPercentUsedPerGroupAndPolicyYear() {
    List<Object> rows = jdbc.query(
        """
        WITH s AS (
          SELECT m.group_id, py.id AS py_id, py.start_date, m.id AS member_id,
                 COALESCE(sum(cc.effective_amount_cents), 0)::bigint AS spend,
                 COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS ded
          FROM member m JOIN policy_year py ON py.group_id = m.group_id
          LEFT JOIN laser l ON l.member_id = m.id AND l.policy_year_id = py.id
          LEFT JOIN claim_current cc ON cc.member_id = m.id AND cc.policy_year_id = py.id
          WHERE m.group_id IN ('GA', 'GB')
          GROUP BY m.group_id, py.id, py.start_date, m.id, l.specific_deductible_cents, py.specific_deductible_cents),
        ranked AS (SELECT *, row_number() OVER (PARTITION BY py_id ORDER BY spend::numeric / ded DESC, member_id) AS rn FROM s)
        SELECT group_id, start_date, member_id, spend, ded FROM ranked WHERE rn <= 3 ORDER BY start_date, group_id, rn""",
        (rs, i) -> tuple(rs.getString("group_id"), rs.getString("start_date"), rs.getString("member_id"),
            rs.getLong("spend")));
    assertThat(rows).containsExactly(
        tuple("GB", "2025-07-01", "B-0006", 6_000_000L),
        tuple("GB", "2025-07-01", "B-0002", 5_800_000L),
        tuple("GB", "2025-07-01", "B-0200", 1_845_323L),
        tuple("GA", "2026-01-01", "A-0005", 5_600_000L),
        tuple("GA", "2026-01-01", "A-0003", 5_100_000L),
        tuple("GA", "2026-01-01", "A-0077", 3_875_277L),
        tuple("GB", "2026-07-01", "B-0010", 5_800_000L),
        tuple("GB", "2026-07-01", "B-0008", 4_500_000L),
        tuple("GB", "2026-07-01", "B-0171", 1_464_554L));
  }
}
