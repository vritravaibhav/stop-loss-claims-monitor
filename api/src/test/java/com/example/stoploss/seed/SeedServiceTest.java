package com.example.stoploss.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.stoploss.TestData;
import com.example.stoploss.support.DbTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeedServiceTest extends DbTest {

  record Snapshot(List<Map<String, Object>> brokerages, List<Map<String, Object>> groups,
      List<Map<String, Object>> policyYears, long members, List<Map<String, Object>> lasers,
      List<Map<String, Object>> users) {}

  Snapshot snapshot() {
    return new Snapshot(
        jdbc.queryForList("SELECT * FROM brokerage ORDER BY id"),
        jdbc.queryForList("SELECT * FROM employer_group ORDER BY id"),
        jdbc.queryForList("SELECT * FROM policy_year ORDER BY id"),
        jdbc.queryForObject("SELECT count(*) FROM member", Long.class),
        jdbc.queryForList("SELECT * FROM laser ORDER BY member_id"),
        jdbc.queryForList("SELECT * FROM app_user ORDER BY id"));
  }

  @Test
  void isIdempotentRunningItAgainChangesNothing() {
    // as every start with SEED_ON_STARTUP=true does
    Snapshot before = snapshot();
    assertThat(before.policyYears()).hasSize(5);
    assertThat(before.members()).isEqualTo(1750);
    assertThat(seedService.seedDatabase(ReferenceData.load(TestData.DATA_DIR)))
        .containsEntry("members", 1750).containsEntry("policyYears", 5).containsEntry("lasers", 2);
    seedService.seedDatabase(ReferenceData.load(TestData.DATA_DIR));
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void stillRejectsOverlappingPolicyYearsOnInsertAndWhenAnUpsertMovesTheEndDate() {
    assertThatThrownBy(() -> jdbc.update(
        """
        INSERT INTO policy_year (group_id, start_date, end_date, specific_deductible_cents)
        VALUES ('GB', '2026-01-01', '2026-12-31', 100)"""))
        .hasMessageContaining("overlaps another policy year of group GB");
    assertThatThrownBy(() -> jdbc.update(
        """
        INSERT INTO policy_year (group_id, start_date, end_date, specific_deductible_cents)
        VALUES ('GB', '2025-07-01', '2026-07-15', 100)
        ON CONFLICT (group_id, start_date) DO UPDATE SET end_date = EXCLUDED.end_date"""))
        .hasMessageContaining("overlaps another policy year of group GB");
  }

  @Test
  void loadsTheReferenceCsvs() {
    ReferenceData data = ReferenceData.load(TestData.DATA_DIR);
    assertThat(data.groups()).extracting(ReferenceData.Group::id).containsExactly("GA", "GB", "GC", "GD");
    assertThat(data.policyYears()).contains(new ReferenceData.PolicyYear("GB", "2026-07-01", "2027-06-30", 8_000_000));
    assertThat(data.lasers()).containsExactly(
        new ReferenceData.Laser("GA", "A-0007", "2026-01-01", 12_000_000),
        new ReferenceData.Laser("GC", "C-0011", "2026-04-01", 10_000_000));
    assertThat(data.members()).hasSize(1750);
    assertThat(data.members().getFirst())
        .isEqualTo(new ReferenceData.Member("A-0001", "GA", "Ryan", "Johnson", "2008-11-02", "Employee"));
  }
}
