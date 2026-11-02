package com.example.stoploss.upload;

import static com.example.stoploss.TestData.GA_HEADER;
import static com.example.stoploss.TestData.GB_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.stoploss.support.DbTest;
import com.example.stoploss.upload.UploadResult.AlertRaised;
import com.example.stoploss.upload.UploadResult.Counts;
import com.example.stoploss.upload.UploadResult.RowError;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The claim-version, spend and alert rules end to end through the ingest transaction and the real schema. */
class IngestServiceTest extends DbTest {

  @BeforeEach
  void reset() {
    resetClaims();
  }

  static BigDecimal n(String s) {
    return new BigDecimal(s);
  }

  static List<Integer> thresholds(UploadResult r) {
    return r.alertsRaised().stream().map(AlertRaised::threshold).toList();
  }

  @Nested
  class Idempotency {
    @Test
    void uploadingTheSameFileTwiceChangesNoTotalsAndRaisesNoNewAlerts() {
      UploadResult first = ingestSample("GA_2026-03.csv");
      assertThat(first.counts()).isEqualTo(new Counts(230, 228, 0, 0, 1, 1));
      Map<String, Long> spendAfterFirst = allSpend();
      long alertsAfterFirst = countRows("alert");
      long versionsAfterFirst = countRows("claim_version");

      UploadResult second = ingestSample("GA_2026-03.csv");
      assertThat(second.status()).isEqualTo("processed");
      assertThat(second.sha256()).isEqualTo(first.sha256());
      assertThat(second.counts()).isEqualTo(new Counts(230, 0, 228, 0, 1, 1));
      assertThat(second.alertsRaised()).isEmpty();
      assertThat(allSpend()).isEqualTo(spendAfterFirst);
      assertThat(countRows("alert")).isEqualTo(alertsAfterFirst);
      assertThat(countRows("claim_version")).isEqualTo(versionsAfterFirst);
      assertThat(countRows("upload")).isEqualTo(2); // both uploads are recorded
    }

    @Test
    void aDuplicateRowInsideOneFileIsUnchangedAndV0ThenV1InOneFileLeavesV1Current() {
      UploadResult r = ingestText("GA_dupes.csv",
          GA_HEADER,
          "900001,0,A-0040,03/01/2026,03/05/2026,100.00,PAID,I10",
          "900001,0,A-0040,03/01/2026,03/05/2026,100.00,PAID,I10",
          "900002,0,A-0040,03/01/2026,03/05/2026,100.00,PAID,I10",
          "900002,1,A-0040,03/01/2026,03/06/2026,250.00,PAID,I10");
      assertThat(r.counts().applied()).isEqualTo(3);
      assertThat(r.counts().unchanged()).isEqualTo(1);
      assertThat(r.counts().errors()).isZero();
      assertThat(spendCents("A-0040", "2026-06-01")).isEqualTo(10_000 + 25_000);
    }
  }

  @Nested
  class ClaimVersions {
    @Test
    void anOlderVersionArrivingLaterIsStoredButIgnored() {
      ingestText("GA_a.csv", GA_HEADER, "900010,0,A-0050,03/09/2026,03/27/2026,\"9,000.00\",PAID,C50.911");
      ingestText("GA_b.csv", GA_HEADER, "900010,2,A-0050,03/09/2026,05/14/2026,\"18,000.00\",PAID,C50.911");
      UploadResult late =
          ingestText("GA_c.csv", GA_HEADER, "900010,1,A-0050,03/09/2026,06/09/2026,\"25,000.00\",PAID,C50.911");
      assertThat(late.counts().applied()).isZero();
      assertThat(late.counts().olderIgnored()).isEqualTo(1);
      assertThat(late.counts().errors()).isZero();
      assertThat(spendCents("A-0050", "2026-03-09")).isEqualTo(1_800_000);
      // all three versions are kept for the drill-down
      assertThat(jdbc.queryForList("SELECT version FROM claim_version WHERE claim_id = ? ORDER BY version",
          Integer.class, "900010")).containsExactly(0, 1, 2);
      // re-sending the older version again is "unchanged", not a second older_ignored
      UploadResult again =
          ingestText("GA_c2.csv", GA_HEADER, "900010,1,A-0050,03/09/2026,06/09/2026,\"25,000.00\",PAID,C50.911");
      assertThat(again.counts().unchanged()).isEqualTo(1);
      assertThat(again.counts().olderIgnored()).isZero();
    }

    @Test
    void theSameVersionWithDifferentValuesIsAConflictErrorAndTheFirstValuesAreKept() {
      ingestText("GA_first.csv", GA_HEADER, "900020,0,A-0060,06/29/2026,07/20/2026,478.99,PAID,M54.5");
      UploadResult r = ingestText("GA_second.csv", GA_HEADER, "900020,0,A-0060,06/29/2026,07/20/2026,578.99,PAID,M54.5");
      assertThat(r.counts().applied()).isZero();
      assertThat(r.counts().errors()).isEqualTo(1);
      assertThat(r.errors()).containsExactly(new RowError(2,
          "Conflict: claim 900020 version 0 was already loaded with different values (first loaded from GA_first.csv"
              + " row 2): paid amount 478.99 → 578.99. The first-loaded values were kept",
          "900020,0,A-0060,06/29/2026,07/20/2026,578.99,PAID,M54.5"));
      assertThat(spendCents("A-0060", "2026-06-29")).isEqualTo(47_899);
    }

    @Test
    void claimIdsAreScopedToTheGroup() {
      // the same claim number in GA and GB are different claims
      ingestText("GA_x.csv", GA_HEADER, "555,0,A-0061,03/01/2026,,100.00,PAID,");
      UploadResult r = ingestText("GB_x.csv", GB_HEADER, "555,0,B-0061,2026-03-01,,$200.00,P");
      assertThat(r.counts().applied()).isEqualTo(1);
      assertThat(r.counts().errors()).isZero();
    }
  }

  @Nested
  class ReversalsAndAlertPermanence {
    @Test
    void aReversalMakesTheClaimZeroTheAlertStaysAndRisingAgainRaisesNothing() {
      UploadResult paid =
          ingestText("GA_1.csv", GA_HEADER, "900030,0,A-0009,03/05/2026,03/25/2026,\"30,000.00\",PAID,I25.10");
      assertThat(paid.alertsRaised()).hasSize(1);
      AlertRaised a = paid.alertsRaised().getFirst();
      assertThat(a.memberId()).isEqualTo("A-0009");
      assertThat(a.threshold()).isEqualTo(50);
      assertThat(a.spend()).isEqualTo(n("30000"));
      assertThat(a.deductible()).isEqualTo(n("50000"));
      assertThat(a.pctUsed()).isEqualTo(n("60"));

      UploadResult reversed =
          ingestText("GA_2.csv", GA_HEADER, "900030,1,A-0009,03/05/2026,06/10/2026,\"30,000.00\",REVERSED,I25.10");
      assertThat(reversed.counts().applied()).isEqualTo(1);
      assertThat(reversed.alertsRaised()).isEmpty();
      assertThat(spendCents("A-0009", "2026-03-05")).isZero();
      assertThat(alertsFor("A-0009")).containsExactly(new AlertRow(50, "open", 3_000_000, 5_000_000, "2026-01-01"));

      UploadResult rise =
          ingestText("GA_3.csv", GA_HEADER, "900031,0,A-0009,07/01/2026,07/05/2026,\"35,000.00\",PAID,I25.10");
      assertThat(spendCents("A-0009", "2026-07-01")).isEqualTo(3_500_000);
      assertThat(rise.alertsRaised()).isEmpty(); // 50% already alerted; 70% is below 75%
      assertThat(alertsFor("A-0009")).extracting(AlertRow::threshold).containsExactly(50);
    }

    @Test
    void spendDroppingThenRisingPastAHigherThresholdRaisesOnlyThatOne() {
      // 60% → 50% alert; reversal → 0%; a new 80% claim → only 75% is new (50% stays from before)
      ingestText("GA_d1.csv", GA_HEADER, "900032,0,A-0010,03/05/2026,,\"30,000.00\",PAID,");
      ingestText("GA_d2.csv", GA_HEADER, "900032,1,A-0010,03/05/2026,,\"30,000.00\",REVERSED,");
      UploadResult rise = ingestText("GA_d3.csv", GA_HEADER, "900033,0,A-0010,04/05/2026,,\"40,000.00\",PAID,");
      assertThat(thresholds(rise)).containsExactly(75);
      assertThat(alertsFor("A-0010")).extracting(AlertRow::threshold).containsExactly(50, 75);
    }
  }

  @Nested
  class AlertThresholdsThroughTheDb {
    @Test
    void exactly50PercentFiresAndOneCentLessDoesNot() {
      UploadResult below = ingestText("GA_b.csv", GA_HEADER, "900040,0,A-0070,03/01/2026,,\"24,999.99\",PAID,");
      assertThat(below.alertsRaised()).isEmpty();
      UploadResult exact = ingestText("GA_e.csv", GA_HEADER, "900041,0,A-0070,03/02/2026,,0.01,PAID,");
      assertThat(exact.alertsRaised()).hasSize(1);
      AlertRaised a = exact.alertsRaised().getFirst();
      assertThat(a.memberId()).isEqualTo("A-0070");
      assertThat(a.threshold()).isEqualTo(50);
      assertThat(a.spend()).isEqualTo(n("25000"));
      assertThat(a.pctUsed()).isEqualTo(n("50"));
    }

    @Test
    void oneFileCrossing50And75And100RaisesThreeAlerts() {
      UploadResult r = ingestText("GA_big.csv", GA_HEADER, "900050,0,A-0080,05/28/2026,06/18/2026,\"56,000.00\",PAID,C50.911");
      assertThat(thresholds(r)).containsExactly(50, 75, 100);
      AlertRaised last = r.alertsRaised().get(2);
      assertThat(last.spend()).isEqualTo(n("56000"));
      assertThat(last.deductible()).isEqualTo(n("50000"));
      assertThat(last.pctUsed()).isEqualTo(n("112"));
    }

    @Test
    void aLaserReplacesTheGroupDeductible() {
      // A-0007: $120,000 laser. $65,000 is 130% of the group's $50,000 but only 54.1% of the laser.
      UploadResult r = ingestText("GA_laser.csv",
          GA_HEADER,
          "900060,0,A-0007,03/02/2026,03/22/2026,\"35,000.00\",PAID,K21.9",
          "900061,0,A-0007,06/01/2026,06/21/2026,\"30,000.00\",PAID,J45.909");
      assertThat(r.alertsRaised()).hasSize(1);
      AlertRaised a = r.alertsRaised().getFirst();
      assertThat(a.memberId()).isEqualTo("A-0007");
      assertThat(a.threshold()).isEqualTo(50);
      assertThat(a.spend()).isEqualTo(n("65000"));
      assertThat(a.deductible()).isEqualTo(n("120000"));
      assertThat(a.pctUsed()).isEqualTo(n("54.1"));
    }

    @Test
    void aGcMemberWithALaserUsesItToo() {
      // C-0011: $100,000
      UploadResult r = ingestText("GC_2026-05.csv", GB_HEADER, "C1,1,C-0011,2026-05-01,,\"$60,000.00\",P");
      assertThat(r.alertsRaised()).extracting(AlertRaised::threshold, AlertRaised::deductible)
          .containsExactly(org.assertj.core.groups.Tuple.tuple(50, n("100000")));
    }
  }

  @Nested
  class PolicyYearsAndMovedClaims {
    @Test
    void aServiceDateOutsideEveryPolicyYearIsStoredButCountsTowardNoSpend() {
      UploadResult r = ingestText("GA_old.csv", GA_HEADER, "900070,0,A-0090,12/13/2025,01/02/2026,\"90,000.00\",PAID,J45.909");
      assertThat(r.counts().applied()).isEqualTo(1);
      assertThat(r.counts().errors()).isZero();
      assertThat(r.alertsRaised()).isEmpty();
      Map<String, Object> cc = jdbc.queryForMap( // exactly one row
          "SELECT policy_year_id, effective_amount_cents FROM claim_current WHERE claim_id = ?", "900070");
      assertThat(cc.get("policy_year_id")).isNull();
      assertThat(cc.get("effective_amount_cents")).isEqualTo(9_000_000L);
      assertThat(allSpend()).isEmpty();
    }

    @Test
    void claimsCountByServiceDateNotPaidDate() {
      // GB: DOS 2026-06-30 paid 2026-07-09 is 2025-26
      ingestText("GB_dos.csv", GB_HEADER, "7709001,1,B-0006,2026-06-30,2026-07-09,$45000.00,P");
      assertThat(spendCents("B-0006", "2026-06-30")).isEqualTo(4_500_000);
      assertThat(spendCents("B-0006", "2026-07-01")).isZero();
      // 45,000 / 75,000 = 60% → 50% alert in the 2025-07-01 policy year
      assertThat(alertsFor("B-0006")).extracting(AlertRow::threshold, AlertRow::startDate)
          .containsExactly(org.assertj.core.groups.Tuple.tuple(50, "2025-07-01"));
    }

    @Test
    void aNewerVersionMovingAClaimToAnotherMemberRecomputesBothMembers() {
      ingestText("GA_m1.csv", GA_HEADER, "900080,0,A-0100,03/01/2026,,\"30,000.00\",PAID,");
      assertThat(spendCents("A-0100", "2026-03-01")).isEqualTo(3_000_000);
      UploadResult moved = ingestText("GA_m2.csv", GA_HEADER, "900080,1,A-0101,03/01/2026,,\"30,000.00\",PAID,");
      assertThat(spendCents("A-0100", "2026-03-01")).isZero();
      assertThat(spendCents("A-0101", "2026-03-01")).isEqualTo(3_000_000);
      assertThat(moved.alertsRaised()).extracting(AlertRaised::memberId, AlertRaised::threshold)
          .containsExactly(org.assertj.core.groups.Tuple.tuple("A-0101", 50));
      // A-0100's alert from before the move stays
      assertThat(alertsFor("A-0100")).extracting(AlertRow::threshold).containsExactly(50);
    }

    @Test
    void aNewerVersionMovingAClaimToAnotherPolicyYearRecomputesBothYears() {
      // like GB claim 7701086
      ingestText("GB_p1.csv", GB_HEADER, "7709010,1,B-0008,2026-06-30,2026-07-14,$40000.00,P");
      assertThat(spendCents("B-0008", "2025-07-01")).isEqualTo(4_000_000);
      ingestText("GB_p2.csv", GB_HEADER, "7709010,2,B-0008,2026-07-01,2026-08-11,$40000.00,P");
      assertThat(spendCents("B-0008", "2025-07-01")).isZero();
      assertThat(spendCents("B-0008", "2026-07-01")).isEqualTo(4_000_000);
      assertThat(alertsFor("B-0008")).extracting(AlertRow::startDate, AlertRow::threshold).containsExactly(
          org.assertj.core.groups.Tuple.tuple("2025-07-01", 50), // 40k/75k = 53% (raised before the move, stays)
          org.assertj.core.groups.Tuple.tuple("2026-07-01", 50)); // 40k/80k = 50% exactly
    }
  }

  @Nested
  class BadRowsAndRejectedFiles {
    @Test
    void reportsUnknownMembersAndMembersOfAnotherGroupWithThePhysicalRowNumber() {
      UploadResult r = ingestText("GA_bad.csv",
          GA_HEADER,
          "900090,0,A-0999,02/02/2026,02/12/2026,800.00,PAID,C50.911",
          "900091,0,B-0004,03/02/2026,03/12/2026,650.00,PAID,J45.909",
          "900092,0,A-0001,03/02/2026,03/12/2026,650.00,PAID,J45.909");
      assertThat(r.counts()).isEqualTo(new Counts(3, 1, 0, 0, 0, 2));
      assertThat(r.errors()).extracting(RowError::rowNumber, RowError::reason).containsExactly(
          org.assertj.core.groups.Tuple.tuple(2, "Unknown member \"A-0999\""),
          org.assertj.core.groups.Tuple.tuple(3, "Member \"B-0004\" belongs to group GB, not GA"));
      List<RowError> stored = jdbc.query(
          "SELECT row_number, reason, raw FROM upload_row_error WHERE upload_id = ? ORDER BY row_number",
          (rs, i) -> new RowError(rs.getInt(1), rs.getString(2), rs.getString(3)), r.id());
      assertThat(stored).isEqualTo(r.errors());
    }

    @Test
    void rejectsAFileWhoseNamePrefixIsNotAKnownGroupAndRecordsIt() {
      UploadResult r = ingestText("GX_2026-01.csv", GA_HEADER, "1,0,A-0001,01/01/2026,,1.00,PAID,");
      assertThat(r.status()).isEqualTo("rejected");
      assertThat(r.groupId()).isNull();
      assertThat(r.layout()).isNull();
      assertThat(r.counts().rows()).isZero();
      assertThat(r.rejectionReason()).contains("Unknown group \"GX\"");
      assertThat(jdbc.queryForMap("SELECT status, rejection_reason FROM upload WHERE id = ?", r.id()))
          .isEqualTo(Map.of("status", "rejected", "rejection_reason", r.rejectionReason()));
      assertThat(countRows("claim_version")).isZero();
    }

    @Test
    void rejectsAFileNameWithoutAGroupPrefix() {
      UploadResult r = ingestText("claims.csv", GA_HEADER);
      assertThat(r.status()).isEqualTo("rejected");
      assertThat(r.rejectionReason()).contains("must start with the group ID");
    }

    @Test
    void rejectsAnUnrecognisedHeaderAndRecordsTheGroup() {
      UploadResult r = ingestText("GA_weird.csv", "id;member;amount", "1;A-0001;10.00");
      assertThat(r.status()).isEqualTo("rejected");
      assertThat(r.groupId()).isEqualTo("GA");
      assertThat(r.layout()).isNull();
      assertThat(r.rejectionReason()).startsWith("Unrecognised header");
    }

    @Test
    void acceptsEitherLayoutForAnyGroup() {
      // layout comes from the header
      UploadResult r = ingestText("GD_2026-03.csv", GA_HEADER, "1,0,D-0001,03/01/2026,,\"26,000.00\",PAID,");
      assertThat(r.status()).isEqualTo("processed");
      assertThat(r.layout()).isEqualTo("tpa_one");
      assertThat(r.counts().applied()).isEqualTo(1);
      assertThat(thresholds(r)).containsExactly(50);
    }
  }

  @Nested
  class Concurrency {
    <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
      try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
        List<Future<T>> futures = pool.invokeAll(tasks);
        List<T> out = new java.util.ArrayList<>();
        for (Future<T> f : futures) out.add(f.get());
        return out;
      }
    }

    @Test
    void twoSimultaneousUploadsOfTheSameFileSerialiseOneAppliesTheOtherIsAllUnchanged() throws Exception {
      List<UploadResult> r = concurrently(List.of(() -> ingestSample("GA_2026-04.csv"), () -> ingestSample("GA_2026-04.csv")));
      UploadResult a = r.get(0);
      UploadResult b = r.get(1);
      assertThat(List.of(a.counts().applied(), b.counts().applied())).containsExactlyInAnyOrder(0, 226);
      assertThat(a.counts().unchanged() + b.counts().unchanged()).isEqualTo(226);
      UploadResult applier = a.counts().applied() > 0 ? a : b;
      UploadResult other = applier == a ? b : a;
      assertThat(applier.alertsRaised()).isNotEmpty();
      assertThat(other.alertsRaised()).isEmpty();
      assertThat(countRows("alert")).isEqualTo(applier.alertsRaised().size());
    }

    @Test
    void simultaneousUploadsOfDifferentFilesForOneGroupGiveTheSameTotalsAsSequentialLoading() throws Exception {
      List<String> files = List.of("GA_2026-01.csv", "GA_2026-02.csv", "GA_2026-03.csv");
      for (String f : files) ingestSample(f);
      Map<String, Long> sequential = allSpend();
      long sequentialAlerts = countRows("alert");

      resetClaims();
      concurrently(files.stream().<Callable<UploadResult>>map(f -> () -> ingestSample(f)).toList());
      assertThat(allSpend()).isEqualTo(sequential);
      assertThat(countRows("alert")).isEqualTo(sequentialAlerts);
    }
  }
}
