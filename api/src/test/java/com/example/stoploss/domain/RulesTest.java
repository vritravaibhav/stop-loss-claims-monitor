package com.example.stoploss.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.stoploss.domain.Versions.ClaimHistory;
import com.example.stoploss.domain.Versions.Decision;
import com.example.stoploss.domain.Versions.Outcome;
import com.example.stoploss.domain.Versions.StoredVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Claim-version rules (STOP_LOSS_RULES.md rules 3, 5, 6) and alert thresholds, without a database. */
class RulesTest {
  static StoredVersion v(int version) {
    return new StoredVersion(version, "A-0013", "2026-03-09", "2026-03-27", 900_000, "paid", "C50.911", null, null);
  }

  static StoredVersion withAmount(StoredVersion s, long cents) {
    return new StoredVersion(s.version(), s.memberId(), s.serviceDate(), s.paidDate(), cents, s.status(),
        s.diagnosisCode(), s.filename(), s.rowNumber());
  }

  static ClaimHistory history(StoredVersion... versions) {
    Map<String, ClaimHistory> m = new HashMap<>();
    for (StoredVersion x : versions) Versions.recordVersion(m, "c", x);
    return m.get("c");
  }

  static Decision decide(ClaimHistory h, StoredVersion incoming) {
    return Versions.decideVersion(h, incoming.version(), incoming);
  }

  @Nested
  class DecideVersion {
    @Test
    void aClaimNeverSeenBeforeIsNew() {
      assertThat(decide(null, v(0)).outcome()).isEqualTo(Outcome.NEW);
    }

    @Test
    void aHigherVersionIsNewEvenWithAGap() {
      assertThat(decide(history(v(0)), withAmount(v(2), 1_800_000)).outcome()).isEqualTo(Outcome.NEW);
    }

    @Test
    void aVersionLowerThanTheCurrentOneIsOlderIgnored() {
      // claim 101991: v1 arrives after v2
      assertThat(decide(history(v(0), withAmount(v(2), 1_800_000)), withAmount(v(1), 2_500_000)).outcome())
          .isEqualTo(Outcome.OLDER_IGNORED);
    }

    @Test
    void theSameVersionWithIdenticalValuesIsUnchanged() {
      assertThat(decide(history(v(0)), v(0)).outcome()).isEqualTo(Outcome.UNCHANGED);
      // including an older version that was itself stored as older_ignored
      assertThat(decide(history(v(0), v(2), v(1)), v(1)).outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void theSameVersionWithADifferentValueIsAConflictListingEveryDifference() {
      Decision d = decide(history(withAmount(v(0), 47_899)), withAmount(v(0), 57_899));
      assertThat(d).isEqualTo(new Decision(Outcome.CONFLICT, List.of("paid amount 478.99 → 578.99")));

      StoredVersion changed = new StoredVersion(0, "A-0014", "2026-03-10", null, 1, "reversed", null, null, null);
      Decision all = decide(history(v(0)), changed);
      assertThat(all.outcome()).isEqualTo(Outcome.CONFLICT);
      assertThat(all.differences()).containsExactly(
          "member A-0013 → A-0014",
          "service date 2026-03-09 → 2026-03-10",
          "paid date 2026-03-27 → (blank)",
          "paid amount 9,000.00 → 0.01",
          "status paid → reversed",
          "diagnosis code C50.911 → (blank)");
    }

    @Test
    void aConflictOnAnOlderVersionIsStillAConflict() {
      assertThat(decide(history(v(0), v(3)), withAmount(v(0), 1)).outcome()).isEqualTo(Outcome.CONFLICT);
    }

    @Test
    void buildsAReadableConflictReasonWithTheFirstLoadedLocation() {
      StoredVersion first = StoredVersion.of(0, v(0), "GA_2026-07.csv", 22);
      assertThat(Versions.conflictReason("101410", 0, first, List.of("paid amount 478.99 → 578.99"))).isEqualTo(
          "Conflict: claim 101410 version 0 was already loaded with different values (first loaded from GA_2026-07.csv"
              + " row 22): paid amount 478.99 → 578.99. The first-loaded values were kept");
    }

    @Test
    void recordVersionKeepsTheHighestVersionAsCurrent() {
      ClaimHistory h = history(v(2), v(0), v(1));
      assertThat(h.currentVersion()).isEqualTo(2);
      assertThat(new TreeSet<>(h.versions().keySet())).containsExactly(0, 1, 2);
    }
  }

  @Nested
  class AlertThresholds {
    static final long DED = 5_000_000; // $50,000

    @Test
    void exactly50PercentFires() {
      assertThat(Alerts.reachesThreshold(2_500_000, DED, 50)).isTrue();
      assertThat(Alerts.reachedThresholds(2_500_000, DED)).containsExactly(50);
    }

    @Test
    void percent4999DoesNotFire() {
      // one cent short of 50%
      assertThat(Alerts.reachesThreshold(2_499_999, DED, 50)).isFalse();
      assertThat(Alerts.reachedThresholds(2_499_500, DED)).isEmpty();
    }

    @Test
    void exactly75AndExactly100Fire() {
      assertThat(Alerts.reachedThresholds(3_750_000, DED)).containsExactly(50, 75);
      assertThat(Alerts.reachedThresholds(5_000_000, DED)).containsExactly(50, 75, 100);
      assertThat(Alerts.reachedThresholds(4_999_999, DED)).containsExactly(50, 75);
    }

    @Test
    void oneFileCrossing50And75And100RaisesAllThree() {
      // A-0005: a single $56,000 claim against $50,000
      assertThat(Alerts.newAlertThresholds(5_600_000, DED, List.of())).containsExactly(50, 75, 100);
    }

    @Test
    void thresholdsAlreadyAlertedAreNotRaisedAgain() {
      // drop then rise
      assertThat(Alerts.newAlertThresholds(5_600_000, DED, List.of(50, 75, 100))).isEmpty();
      assertThat(Alerts.newAlertThresholds(4_000_000, DED, List.of(50))).containsExactly(75);
    }

    @Test
    void usesTheLaserDeductibleWhenGiven() {
      // A-0007: $65,000 of $120,000 is 54%
      assertThat(Alerts.reachedThresholds(6_500_000, 12_000_000)).containsExactly(50);
    }

    @Test
    void worksWithOddDeductiblesWithoutRoundingErrors() {
      // 50% of 3 cents is 1.5 cents → 1 cent is short, 2 cents reaches
      assertThat(Alerts.reachesThreshold(1, 3, 50)).isFalse();
      assertThat(Alerts.reachesThreshold(2, 3, 50)).isTrue();
      assertThat(Alerts.reachesThreshold(Long.MAX_VALUE, Long.MAX_VALUE, 100)).isTrue();
    }
  }
}
