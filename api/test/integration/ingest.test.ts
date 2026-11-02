import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { ingestClaimFile } from '../../src/services/ingest.js';
import {
  CLAIMS_DIR,
  GA_HEADER,
  GB_HEADER,
  alertsFor,
  allSpend,
  countRows,
  ingestText,
  setupTestDb,
  spendCents,
  type TestDb,
} from '../helpers/db.js';

let t: TestDb;
beforeAll(async () => {
  t = await setupTestDb('ingest');
});
afterAll(async () => {
  await t?.close();
});
beforeEach(async () => {
  await t.resetClaims();
});

const sampleFile = (name: string) => ({ filename: name, content: readFileSync(path.join(CLAIMS_DIR, name)), uploadedBy: 'test' });

describe('idempotency', () => {
  it('uploading the same file twice changes no totals and raises no new alerts', async () => {
    const first = await ingestClaimFile(t.db, sampleFile('GA_2026-03.csv'));
    expect(first.counts).toEqual({ rows: 230, applied: 228, unchanged: 0, olderIgnored: 0, summary: 1, errors: 1 });
    const spendAfterFirst = await allSpend(t.db);
    const alertsAfterFirst = await countRows(t.db, 'alert');
    const versionsAfterFirst = await countRows(t.db, 'claim_version');

    const second = await ingestClaimFile(t.db, sampleFile('GA_2026-03.csv'));
    expect(second.status).toBe('processed');
    expect(second.sha256).toBe(first.sha256);
    expect(second.counts).toEqual({ rows: 230, applied: 0, unchanged: 228, olderIgnored: 0, summary: 1, errors: 1 });
    expect(second.alertsRaised).toEqual([]);
    expect(await allSpend(t.db)).toEqual(spendAfterFirst);
    expect(await countRows(t.db, 'alert')).toBe(alertsAfterFirst);
    expect(await countRows(t.db, 'claim_version')).toBe(versionsAfterFirst);
    expect(await countRows(t.db, 'upload')).toBe(2); // both uploads are recorded
  });

  it('a duplicate row inside one file is unchanged; v0 then v1 in one file leaves v1 current', async () => {
    const r = await ingestText(t.db, 'GA_dupes.csv', [
      GA_HEADER,
      '900001,0,A-0040,03/01/2026,03/05/2026,100.00,PAID,I10',
      '900001,0,A-0040,03/01/2026,03/05/2026,100.00,PAID,I10',
      '900002,0,A-0040,03/01/2026,03/05/2026,100.00,PAID,I10',
      '900002,1,A-0040,03/01/2026,03/06/2026,250.00,PAID,I10',
    ]);
    expect(r.counts).toMatchObject({ applied: 3, unchanged: 1, errors: 0 });
    expect(await spendCents(t.db, 'A-0040', '2026-06-01')).toBe(10_000 + 25_000);
  });
});

describe('claim versions', () => {
  it('an older version arriving later is stored but ignored (rule 5)', async () => {
    await ingestText(t.db, 'GA_a.csv', [GA_HEADER, '900010,0,A-0050,03/09/2026,03/27/2026,"9,000.00",PAID,C50.911']);
    await ingestText(t.db, 'GA_b.csv', [GA_HEADER, '900010,2,A-0050,03/09/2026,05/14/2026,"18,000.00",PAID,C50.911']);
    const late = await ingestText(t.db, 'GA_c.csv', [GA_HEADER, '900010,1,A-0050,03/09/2026,06/09/2026,"25,000.00",PAID,C50.911']);
    expect(late.counts).toMatchObject({ applied: 0, olderIgnored: 1, errors: 0 });
    expect(await spendCents(t.db, 'A-0050', '2026-03-09')).toBe(1_800_000);
    // all three versions are kept for the drill-down
    const v = await t.db.query('SELECT version FROM claim_version WHERE claim_id = $1 ORDER BY version', ['900010']);
    expect(v.rows.map((r) => r.version)).toEqual([0, 1, 2]);
    // re-sending the older version again is "unchanged", not a second older_ignored
    const again = await ingestText(t.db, 'GA_c2.csv', [GA_HEADER, '900010,1,A-0050,03/09/2026,06/09/2026,"25,000.00",PAID,C50.911']);
    expect(again.counts).toMatchObject({ unchanged: 1, olderIgnored: 0 });
  });

  it('the same version with different values is a conflict error; the first values are kept (rule 6)', async () => {
    await ingestText(t.db, 'GA_first.csv', [GA_HEADER, '900020,0,A-0060,06/29/2026,07/20/2026,478.99,PAID,M54.5']);
    const r = await ingestText(t.db, 'GA_second.csv', [GA_HEADER, '900020,0,A-0060,06/29/2026,07/20/2026,578.99,PAID,M54.5']);
    expect(r.counts).toMatchObject({ applied: 0, errors: 1 });
    expect(r.errors).toEqual([
      {
        rowNumber: 2,
        reason:
          'Conflict: claim 900020 version 0 was already loaded with different values (first loaded from GA_first.csv row 2): paid amount 478.99 → 578.99. The first-loaded values were kept',
        raw: '900020,0,A-0060,06/29/2026,07/20/2026,578.99,PAID,M54.5',
      },
    ]);
    expect(await spendCents(t.db, 'A-0060', '2026-06-29')).toBe(47_899);
  });

  it('claim IDs are scoped to the group: the same claim number in GA and GB are different claims', async () => {
    await ingestText(t.db, 'GA_x.csv', [GA_HEADER, '555,0,A-0061,03/01/2026,,100.00,PAID,']);
    const r = await ingestText(t.db, 'GB_x.csv', [GB_HEADER, '555,0,B-0061,2026-03-01,,$200.00,P']);
    expect(r.counts).toMatchObject({ applied: 1, errors: 0 });
  });
});

describe('reversals and alert permanence', () => {
  it('a reversal makes the claim $0, the alert stays, and rising again raises nothing', async () => {
    const paid = await ingestText(t.db, 'GA_1.csv', [GA_HEADER, '900030,0,A-0009,03/05/2026,03/25/2026,"30,000.00",PAID,I25.10']);
    expect(paid.alertsRaised).toEqual([
      { id: expect.any(Number), memberId: 'A-0009', threshold: 50, spend: 30000, deductible: 50000, pctUsed: 60 },
    ]);

    const reversed = await ingestText(t.db, 'GA_2.csv', [GA_HEADER, '900030,1,A-0009,03/05/2026,06/10/2026,"30,000.00",REVERSED,I25.10']);
    expect(reversed.counts).toMatchObject({ applied: 1 });
    expect(reversed.alertsRaised).toEqual([]);
    expect(await spendCents(t.db, 'A-0009', '2026-03-05')).toBe(0);
    expect(await alertsFor(t.db, 'A-0009')).toEqual([
      { threshold: 50, status: 'open', spend_cents_at_trigger: 3_000_000, deductible_cents_at_trigger: 5_000_000, start_date: '2026-01-01' },
    ]);

    const rise = await ingestText(t.db, 'GA_3.csv', [GA_HEADER, '900031,0,A-0009,07/01/2026,07/05/2026,"35,000.00",PAID,I25.10']);
    expect(await spendCents(t.db, 'A-0009', '2026-07-01')).toBe(3_500_000);
    expect(rise.alertsRaised).toEqual([]); // 50% already alerted; 70% is below 75%
    expect((await alertsFor(t.db, 'A-0009')).map((a) => a.threshold)).toEqual([50]);
  });
});

describe('alert thresholds through the DB', () => {
  it('exactly 50% fires and one cent less does not', async () => {
    const below = await ingestText(t.db, 'GA_b.csv', [GA_HEADER, '900040,0,A-0070,03/01/2026,,"24,999.99",PAID,']);
    expect(below.alertsRaised).toEqual([]);
    const exact = await ingestText(t.db, 'GA_e.csv', [GA_HEADER, '900041,0,A-0070,03/02/2026,,0.01,PAID,']);
    expect(exact.alertsRaised).toEqual([expect.objectContaining({ memberId: 'A-0070', threshold: 50, spend: 25000, pctUsed: 50 })]);
  });

  it('one file crossing 50, 75 and 100 raises three alerts', async () => {
    const r = await ingestText(t.db, 'GA_big.csv', [GA_HEADER, '900050,0,A-0080,05/28/2026,06/18/2026,"56,000.00",PAID,C50.911']);
    expect(r.alertsRaised.map((a) => a.threshold)).toEqual([50, 75, 100]);
    expect(r.alertsRaised[2]).toMatchObject({ spend: 56000, deductible: 50000, pctUsed: 112 });
  });

  it('a laser replaces the group deductible (A-0007: $120,000)', async () => {
    const r = await ingestText(t.db, 'GA_laser.csv', [
      GA_HEADER,
      '900060,0,A-0007,03/02/2026,03/22/2026,"35,000.00",PAID,K21.9',
      '900061,0,A-0007,06/01/2026,06/21/2026,"30,000.00",PAID,J45.909',
    ]);
    // $65,000 is 130% of the group's $50,000 but only 54.1% of the laser
    expect(r.alertsRaised).toEqual([
      expect.objectContaining({ memberId: 'A-0007', threshold: 50, spend: 65000, deductible: 120000, pctUsed: 54.1 }),
    ]);
  });

  it('a GC member with a laser uses it too (C-0011: $100,000)', async () => {
    const r = await ingestText(t.db, 'GC_2026-05.csv', [GB_HEADER, 'C1,1,C-0011,2026-05-01,,"$60,000.00",P']);
    expect(r.alertsRaised.map((a) => [a.threshold, a.deductible])).toEqual([[50, 100000]]);
  });
});

describe('policy years and moved claims', () => {
  it('a service date outside every policy year is stored but counts toward no spend', async () => {
    const r = await ingestText(t.db, 'GA_old.csv', [GA_HEADER, '900070,0,A-0090,12/13/2025,01/02/2026,"90,000.00",PAID,J45.909']);
    expect(r.counts).toMatchObject({ applied: 1, errors: 0 });
    expect(r.alertsRaised).toEqual([]);
    const cc = await t.db.query('SELECT policy_year_id, effective_amount_cents FROM claim_current WHERE claim_id = $1', ['900070']);
    expect(cc.rows).toEqual([{ policy_year_id: null, effective_amount_cents: 9_000_000 }]);
    expect(await allSpend(t.db)).toEqual({});
  });

  it('claims count by service date, not paid date (GB: DOS 2026-06-30 paid 2026-07-09 is 2025-26)', async () => {
    await ingestText(t.db, 'GB_dos.csv', [GB_HEADER, '7709001,1,B-0006,2026-06-30,2026-07-09,$45000.00,P']);
    expect(await spendCents(t.db, 'B-0006', '2026-06-30')).toBe(4_500_000);
    expect(await spendCents(t.db, 'B-0006', '2026-07-01')).toBe(0);
    // 45,000 / 75,000 = 60% → 50% alert in the 2025-07-01 policy year
    expect(await alertsFor(t.db, 'B-0006')).toEqual([expect.objectContaining({ threshold: 50, start_date: '2025-07-01' })]);
  });

  it('a newer version moving a claim to another member recomputes both members', async () => {
    await ingestText(t.db, 'GA_m1.csv', [GA_HEADER, '900080,0,A-0100,03/01/2026,,"30,000.00",PAID,']);
    expect(await spendCents(t.db, 'A-0100', '2026-03-01')).toBe(3_000_000);
    const moved = await ingestText(t.db, 'GA_m2.csv', [GA_HEADER, '900080,1,A-0101,03/01/2026,,"30,000.00",PAID,']);
    expect(await spendCents(t.db, 'A-0100', '2026-03-01')).toBe(0);
    expect(await spendCents(t.db, 'A-0101', '2026-03-01')).toBe(3_000_000);
    expect(moved.alertsRaised.map((a) => [a.memberId, a.threshold])).toEqual([['A-0101', 50]]);
    // A-0100's alert from before the move stays
    expect((await alertsFor(t.db, 'A-0100')).map((a) => a.threshold)).toEqual([50]);
  });

  it('a newer version moving a claim to another policy year recomputes both years (GB claim 7701086)', async () => {
    await ingestText(t.db, 'GB_p1.csv', [GB_HEADER, '7709010,1,B-0008,2026-06-30,2026-07-14,$40000.00,P']);
    expect(await spendCents(t.db, 'B-0008', '2025-07-01')).toBe(4_000_000);
    await ingestText(t.db, 'GB_p2.csv', [GB_HEADER, '7709010,2,B-0008,2026-07-01,2026-08-11,$40000.00,P']);
    expect(await spendCents(t.db, 'B-0008', '2025-07-01')).toBe(0);
    expect(await spendCents(t.db, 'B-0008', '2026-07-01')).toBe(4_000_000);
    expect((await alertsFor(t.db, 'B-0008')).map((a) => [a.start_date, a.threshold])).toEqual([
      ['2025-07-01', 50], // 40k/75k = 53% (raised before the move, stays)
      ['2026-07-01', 50], // 40k/80k = 50% exactly
    ]);
  });
});

describe('bad rows and rejected files', () => {
  it('reports unknown members and members of another group with the physical row number', async () => {
    const r = await ingestText(t.db, 'GA_bad.csv', [
      GA_HEADER,
      '900090,0,A-0999,02/02/2026,02/12/2026,800.00,PAID,C50.911',
      '900091,0,B-0004,03/02/2026,03/12/2026,650.00,PAID,J45.909',
      '900092,0,A-0001,03/02/2026,03/12/2026,650.00,PAID,J45.909',
    ]);
    expect(r.counts).toEqual({ rows: 3, applied: 1, unchanged: 0, olderIgnored: 0, summary: 0, errors: 2 });
    expect(r.errors.map((e) => [e.rowNumber, e.reason])).toEqual([
      [2, 'Unknown member "A-0999"'],
      [3, 'Member "B-0004" belongs to group GB, not GA'],
    ]);
    const stored = await getUploadErrors(r.id);
    expect(stored).toEqual(r.errors);
  });

  it('rejects a file whose name prefix is not a known group, and records it', async () => {
    const r = await ingestText(t.db, 'GX_2026-01.csv', [GA_HEADER, '1,0,A-0001,01/01/2026,,1.00,PAID,']);
    expect(r).toMatchObject({ status: 'rejected', groupId: null, layout: null, counts: { rows: 0 } });
    expect(r.rejectionReason).toMatch(/Unknown group "GX"/);
    const u = await t.db.query('SELECT status, rejection_reason FROM upload WHERE id = $1', [r.id]);
    expect(u.rows[0]).toEqual({ status: 'rejected', rejection_reason: r.rejectionReason });
    expect(await countRows(t.db, 'claim_version')).toBe(0);
  });

  it('rejects a file name without a group prefix', async () => {
    const r = await ingestText(t.db, 'claims.csv', [GA_HEADER]);
    expect(r.status).toBe('rejected');
    expect(r.rejectionReason).toMatch(/must start with the group ID/);
  });

  it('rejects an unrecognised header, and records the group', async () => {
    const r = await ingestText(t.db, 'GA_weird.csv', ['id;member;amount', '1;A-0001;10.00']);
    expect(r).toMatchObject({ status: 'rejected', groupId: 'GA', layout: null });
    expect(r.rejectionReason).toMatch(/^Unrecognised header/);
  });

  it('accepts either layout for any group (layout comes from the header)', async () => {
    const r = await ingestText(t.db, 'GD_2026-03.csv', [GA_HEADER, '1,0,D-0001,03/01/2026,,"26,000.00",PAID,']);
    expect(r).toMatchObject({ status: 'processed', layout: 'tpa_one', counts: { applied: 1 } });
    expect(r.alertsRaised.map((a) => a.threshold)).toEqual([50]);
  });
});

describe('concurrency', () => {
  it('two simultaneous uploads of the same file serialise: one applies, the other is all unchanged', async () => {
    const [a, b] = await Promise.all([
      ingestClaimFile(t.db, sampleFile('GA_2026-04.csv')),
      ingestClaimFile(t.db, sampleFile('GA_2026-04.csv')),
    ]);
    const applied = [a.counts.applied, b.counts.applied].sort((x, y) => x - y);
    expect(applied).toEqual([0, 226]);
    expect(a.counts.unchanged + b.counts.unchanged).toBe(226);
    const [applier, other] = a.counts.applied > 0 ? [a, b] : [b, a];
    expect(applier.alertsRaised.length).toBeGreaterThan(0);
    expect(other.alertsRaised).toEqual([]);
    expect(await countRows(t.db, 'alert')).toBe(applier.alertsRaised.length);
  });

  it('simultaneous uploads of different files for one group give the same totals as sequential loading', async () => {
    const files = ['GA_2026-01.csv', 'GA_2026-02.csv', 'GA_2026-03.csv'];
    for (const f of files) await ingestClaimFile(t.db, sampleFile(f));
    const sequential = await allSpend(t.db);
    const sequentialAlerts = await countRows(t.db, 'alert');

    await t.resetClaims();
    await Promise.all(files.map((f) => ingestClaimFile(t.db, sampleFile(f))));
    expect(await allSpend(t.db)).toEqual(sequential);
    expect(await countRows(t.db, 'alert')).toBe(sequentialAlerts);
  });
});

async function getUploadErrors(uploadId: number) {
  const r = await t.db.query<{ rowNumber: number; reason: string; raw: string }>(
    'SELECT row_number AS "rowNumber", reason, raw FROM upload_row_error WHERE upload_id = $1 ORDER BY row_number',
    [uploadId],
  );
  return r.rows;
}
