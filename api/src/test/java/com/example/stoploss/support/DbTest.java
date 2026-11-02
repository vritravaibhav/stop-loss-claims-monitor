package com.example.stoploss.support;

import com.example.stoploss.TestData;
import com.example.stoploss.auth.TokenVerifier;
import com.example.stoploss.seed.ReferenceData;
import com.example.stoploss.seed.SeedService;
import com.example.stoploss.upload.IngestService;
import com.example.stoploss.upload.UploadResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Base for tests against the real schema in a Testcontainers Postgres, seeded with the real seed code from
 * {@code data/}. Every subclass shares one Spring context (same configuration) and one database, so each test resets
 * the claim tables it depends on. The token verifier is the test fake; the clock is settable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public abstract class DbTest {
  protected static final TestClock CLOCK = new TestClock();
  private static volatile boolean seeded;

  @TestBean protected TokenVerifier tokenVerifier;
  @TestBean protected Clock clock;

  static TokenVerifier tokenVerifier() {
    return new FakeTokenVerifier();
  }

  static Clock clock() {
    return CLOCK;
  }

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected IngestService ingest;
  @Autowired protected SeedService seedService;

  @BeforeEach
  void resetClockAndSeed() {
    CLOCK.reset();
    ensureSeeded();
  }

  /** Seed the reference data once per test run (idempotent anyway); call it from {@code @BeforeAll} too. */
  protected void ensureSeeded() {
    if (!seeded) {
      seedService.seedDatabase(ReferenceData.load(TestData.DATA_DIR));
      seeded = true;
    }
  }

  /** Remove all uploads/claims/alerts but keep the seeded reference data. */
  protected void resetClaims() {
    jdbc.execute("TRUNCATE alert, claim_current, claim_version, upload_row_error, upload RESTART IDENTITY CASCADE");
  }

  protected UploadResult ingestText(String filename, String... lines) {
    return ingest.ingest(filename, TestData.csv(lines), "test");
  }

  protected UploadResult ingestSample(String name) {
    return ingest.ingest(name, TestData.sample(name), "test");
  }

  /** Spend (cents) of one member in the policy year containing {@code onDate}. */
  protected long spendCents(String memberId, String onDate) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(sum(cc.effective_amount_cents), 0)::bigint AS spend
        FROM claim_current cc JOIN policy_year py ON py.id = cc.policy_year_id
        WHERE cc.member_id = ? AND CAST(? AS date) BETWEEN py.start_date AND py.end_date""",
        Long.class, memberId, onDate);
  }

  /** Every (member, policy year) spend, for "nothing changed" comparisons. */
  protected Map<String, Long> allSpend() {
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT member_id, policy_year_id, sum(effective_amount_cents)::bigint AS spend
        FROM claim_current WHERE policy_year_id IS NOT NULL GROUP BY 1, 2 ORDER BY 1, 2""",
        rs -> {
          out.put(rs.getString("member_id") + "|" + rs.getInt("policy_year_id"), rs.getLong("spend"));
        });
    return out;
  }

  public record AlertRow(int threshold, String status, long spendCentsAtTrigger, long deductibleCentsAtTrigger,
      String startDate) {}

  protected List<AlertRow> alertsFor(String memberId) {
    return jdbc.query(
        """
        SELECT a.threshold, a.status, a.spend_cents_at_trigger, a.deductible_cents_at_trigger, py.start_date
        FROM alert a JOIN policy_year py ON py.id = a.policy_year_id
        WHERE a.member_id = ? ORDER BY py.start_date, a.threshold""",
        (rs, i) -> new AlertRow(rs.getInt("threshold"), rs.getString("status"), rs.getLong("spend_cents_at_trigger"),
            rs.getLong("deductible_cents_at_trigger"), rs.getString("start_date")),
        memberId);
  }

  protected long countRows(String table) {
    if (!List.of("alert", "claim_version", "claim_current", "upload").contains(table)) throw new IllegalArgumentException();
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }
}
