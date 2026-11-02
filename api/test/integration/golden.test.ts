/**
 * Golden test: load every sample file in file-name order (exactly like `npm run load-sample` and the analyst's
 * multi-file upload) and check the known traps. Expected per-file counts and alerts were cross-checked against an
 * independent re-implementation of the rules written outside this codebase.
 */
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { compareFileNames } from '../../src/services/loadSample.js';
import { ingestClaimFile } from '../../src/services/ingest.js';
import type { UploadResult } from '../../src/services/uploads.js';
import { CLAIMS_DIR, alertsFor, allSpend, setupTestDb, spendCents, type TestDb } from '../helpers/db.js';

let t: TestDb;
const results = new Map<string, UploadResult>();
const spendBeforeResend: Record<string, number> = {};
const spendAfterResend: Record<string, number> = {};

beforeAll(async () => {
  t = await setupTestDb('golden');
  const files = readdirSync(CLAIMS_DIR).filter((f) => f.endsWith('.csv')).sort(compareFileNames);
  for (const f of files) {
    if (f === 'GA_2026-04_resend.csv') Object.assign(spendBeforeResend, await allSpend(t.db));
    results.set(f, await ingestClaimFile(t.db, { filename: f, content: readFileSync(path.join(CLAIMS_DIR, f)), uploadedBy: 'golden' }));
    if (f === 'GA_2026-04_resend.csv') Object.assign(spendAfterResend, await allSpend(t.db));
  }
});
afterAll(async () => {
  await t?.close();
});

const res = (f: string) => {
  const r = results.get(f);
  if (!r) throw new Error(`${f} was not loaded`);
  return r;
};
const alertKeys = (f: string) => res(f).alertsRaised.map((a) => `${a.memberId}@${a.threshold}`);

describe('golden: all sample files in file-name order', () => {
  it('loads files in byte order, so GA_2026-04.csv comes before GA_2026-04_resend.csv', () => {
    const order = [...results.keys()];
    expect(order.indexOf('GA_2026-04.csv')).toBe(order.indexOf('GA_2026-04_resend.csv') - 1);
    expect(order).toHaveLength(16);
  });

  it('per-file counts', () => {
    const counts = Object.fromEntries([...results].map(([f, r]) => [f, r.counts]));
    const c = (rows: number, applied: number, unchanged: number, olderIgnored: number, summary: number, errors: number) => ({
      rows, applied, unchanged, olderIgnored, summary, errors,
    });
    expect(counts).toEqual({
      'GA_2026-01.csv': c(222, 221, 0, 0, 1, 0),
      'GA_2026-02.csv': c(224, 222, 0, 0, 1, 1),
      'GA_2026-03.csv': c(230, 228, 0, 0, 1, 1),
      'GA_2026-04.csv': c(227, 226, 0, 0, 1, 0),
      'GA_2026-04_resend.csv': c(227, 0, 226, 0, 1, 0),
      'GA_2026-05.csv': c(243, 241, 0, 0, 1, 1),
      'GA_2026-06.csv': c(243, 241, 0, 1, 1, 0),
      'GA_2026-07.csv': c(247, 245, 1, 0, 1, 0),
      'GA_2026-08.csv': c(252, 250, 0, 0, 1, 1),
      'GA_2026-09.csv': c(269, 268, 0, 0, 1, 0),
      'GB_2026-04.csv': c(181, 181, 0, 0, 0, 0),
      'GB_2026-05.csv': c(183, 183, 0, 0, 0, 0),
      'GB_2026-06.csv': c(188, 188, 0, 0, 0, 0),
      'GB_2026-07.csv': c(193, 193, 0, 0, 0, 0),
      'GB_2026-08.csv': c(200, 200, 0, 0, 0, 0),
      'GB_2026-09.csv': c(218, 218, 0, 0, 0, 0),
    });
    for (const r of results.values()) {
      expect(r.status).toBe('processed');
      expect(r.layout).toBe(r.groupId === 'GA' ? 'tpa_one' : 'tpa_two');
    }
  });

  it('every bad row, with its physical line number and reason', () => {
    const errors = [...results].flatMap(([f, r]) => r.errors.map((e) => [f, e.rowNumber, e.reason]));
    expect(errors).toEqual([
      ['GA_2026-02.csv', 45, 'Unknown member "A-0999"'],
      ['GA_2026-03.csv', 140, 'Member "B-0004" belongs to group GB, not GA'],
      ['GA_2026-05.csv', 18, 'Invalid paid amount "N/A"'],
      [
        'GA_2026-08.csv',
        7,
        'Conflict: claim 101410 version 0 was already loaded with different values (first loaded from GA_2026-07.csv row 22): paid amount 478.99 → 578.99. The first-loaded values were kept',
      ],
    ]);
    expect(res('GA_2026-02.csv').errors[0]!.raw).toBe('101993,0,A-0999,02/02/2026,02/12/2026,800.00,PAID,C50.911');
  });

  it('GA_2026-01 line 223 TRAILER is a summary row; out-of-policy-year service dates are stored but count nowhere', async () => {
    expect(res('GA_2026-01.csv').counts.summary).toBe(1);
    const r = await t.db.query<{ n: number; amount: number }>(
      `SELECT count(*)::bigint AS n, sum(effective_amount_cents)::bigint AS amount
       FROM claim_current WHERE group_id = 'GA' AND policy_year_id IS NULL`,
    );
    expect(r.rows[0]!.n).toBeGreaterThan(0);
    const late2025 = await t.db.query<{ n: number }>(
      `SELECT count(*)::bigint AS n FROM claim_current WHERE group_id = 'GA' AND service_date < '2026-01-01' AND policy_year_id IS NOT NULL`,
    );
    expect(late2025.rows[0]!.n).toBe(0);
  });

  it('claim 101991 (A-0013): v0 9,000 → v2 18,000 → late v1 25,000 is older_ignored; effective 18,000', async () => {
    const cc = await t.db.query(
      `SELECT version, effective_amount_cents FROM claim_current WHERE group_id = 'GA' AND claim_id = '101991'`,
    );
    expect(cc.rows).toEqual([{ version: 2, effective_amount_cents: 1_800_000 }]);
    const versions = await t.db.query(
      `SELECT cv.version, cv.paid_amount_cents, u.filename, cv.row_number
       FROM claim_version cv JOIN upload u ON u.id = cv.upload_id
       WHERE cv.group_id = 'GA' AND cv.claim_id = '101991' ORDER BY cv.version`,
    );
    expect(versions.rows).toEqual([
      { version: 0, paid_amount_cents: 900_000, filename: 'GA_2026-03.csv', row_number: 174 },
      { version: 1, paid_amount_cents: 2_500_000, filename: 'GA_2026-06.csv', row_number: 85 },
      { version: 2, paid_amount_cents: 1_800_000, filename: 'GA_2026-05.csv', row_number: 9 },
    ]);
    expect(res('GA_2026-06.csv').counts.olderIgnored).toBe(1);
  });

  it('claim 101988 (A-0009): $30,000 raises 50% in GA_2026-03, the GA_2026-06 reversal makes it $0, the alert stays and is never duplicated', async () => {
    expect(alertKeys('GA_2026-03.csv')).toContain('A-0009@50');
    const cc = await t.db.query(`SELECT version, effective_amount_cents FROM claim_current WHERE group_id = 'GA' AND claim_id = '101988'`);
    expect(cc.rows).toEqual([{ version: 1, effective_amount_cents: 0 }]);
    const alerts = await alertsFor(t.db, 'A-0009');
    expect(alerts.map((a) => [a.threshold, a.status, a.spend_cents_at_trigger])).toEqual([[50, 'open', expect.any(Number)]]);
    expect(alerts[0]!.spend_cents_at_trigger).toBeGreaterThanOrEqual(3_000_000);
    const fileCount = [...results.values()].filter((r) => r.alertsRaised.some((a) => a.memberId === 'A-0009')).length;
    expect(fileCount).toBe(1);
  });

  it('GA_2026-04_resend.csv (byte-identical to GA_2026-04.csv) changes nothing', async () => {
    const resend = res('GA_2026-04_resend.csv');
    expect(resend.sha256).toBe(res('GA_2026-04.csv').sha256);
    expect(resend.counts.unchanged).toBe(226);
    expect(resend.alertsRaised).toEqual([]);
    expect(Object.keys(spendBeforeResend).length).toBeGreaterThan(0);
    expect(spendAfterResend).toEqual(spendBeforeResend);
    const inserted = await t.db.query<{ n: number }>(
      `SELECT count(*)::bigint AS n FROM claim_version WHERE upload_id = $1`,
      [resend.id],
    );
    expect(inserted.rows[0]!.n).toBe(0);
  });

  it('GA_2026-07 lines 12 and 124: identical duplicate of claim 101394 v0 in one file → second is unchanged', async () => {
    expect(res('GA_2026-07.csv').counts.unchanged).toBe(1);
    const v = await t.db.query(`SELECT row_number FROM claim_version WHERE group_id = 'GA' AND claim_id = '101394' AND version = 0`);
    expect(v.rows).toEqual([{ row_number: 12 }]);
  });

  it('GA_2026-08 line 7: conflicting claim 101410 v0 keeps the first-loaded 478.99', async () => {
    const cc = await t.db.query(`SELECT effective_amount_cents FROM claim_current WHERE group_id = 'GA' AND claim_id = '101410'`);
    expect(cc.rows).toEqual([{ effective_amount_cents: 47_899 }]);
  });

  it('A-0007 is measured against the $120,000 laser, not $50,000', async () => {
    expect(await spendCents(t.db, 'A-0007', '2026-06-01')).toBe(6_500_000);
    const alerts = await alertsFor(t.db, 'A-0007');
    expect(alerts.map((a) => [a.threshold, a.deductible_cents_at_trigger])).toEqual([[50, 12_000_000]]);
  });

  it('alerts raised per file', () => {
    const perFile = Object.fromEntries([...results].map(([f]) => [f, alertKeys(f)]));
    expect(perFile).toEqual({
      'GA_2026-01.csv': [],
      'GA_2026-02.csv': [],
      'GA_2026-03.csv': ['A-0003@50', 'A-0009@50'],
      'GA_2026-04.csv': ['A-0011@50', 'A-0011@75', 'A-0011@100'],
      'GA_2026-04_resend.csv': [],
      'GA_2026-05.csv': ['A-0003@75'],
      'GA_2026-06.csv': ['A-0005@50', 'A-0005@75', 'A-0005@100', 'A-0007@50', 'A-0071@50', 'A-0162@50'],
      'GA_2026-07.csv': ['A-0003@100', 'A-0077@50', 'A-0200@50'],
      'GA_2026-08.csv': [],
      'GA_2026-09.csv': ['A-0077@75'],
      'GB_2026-04.csv': [],
      'GB_2026-05.csv': ['B-0002@50', 'B-0002@75'],
      'GB_2026-06.csv': [],
      'GB_2026-07.csv': ['B-0006@50', 'B-0006@75'],
      'GB_2026-08.csv': ['B-0008@50', 'B-0012@50'],
      'GB_2026-09.csv': ['B-0010@50'],
    });
  });

  it('alerts total by threshold, and at most one per member/policy year/threshold', async () => {
    const r = await t.db.query<{ threshold: number; n: number }>(
      'SELECT threshold, count(*)::bigint AS n FROM alert GROUP BY threshold ORDER BY threshold',
    );
    expect(r.rows).toEqual([
      { threshold: 50, n: 14 },
      { threshold: 75, n: 6 },
      { threshold: 100, n: 3 },
    ]);
  });

  it('A-0011: 50/75/100 in one file (GA_2026-04), then v1 lowers spend to 74%; the alerts stay', async () => {
    expect(await spendCents(t.db, 'A-0011', '2026-04-02')).toBe(3_700_000);
    expect((await alertsFor(t.db, 'A-0011')).map((a) => a.threshold)).toEqual([50, 75, 100]);
  });

  it('GB: claims count by service date; B-0006 ($45,000 with DOS 2026-06-30 paid in July) is in 2025-26', async () => {
    expect(await spendCents(t.db, 'B-0006', '2026-06-30')).toBe(6_000_000);
    expect((await alertsFor(t.db, 'B-0006')).map((a) => [a.start_date, a.threshold])).toEqual([
      ['2025-07-01', 50],
      ['2025-07-01', 75],
    ]);
  });

  it('GB claim 7701086 moved from 2025-26 (DOS 2026-06-30) to 2026-27 (DOS 2026-07-01): both years recomputed', async () => {
    expect(await spendCents(t.db, 'B-0008', '2026-06-30')).toBe(0);
    expect(await spendCents(t.db, 'B-0008', '2026-07-01')).toBe(4_500_000);
    expect((await alertsFor(t.db, 'B-0008')).map((a) => [a.start_date, a.threshold])).toEqual([['2026-07-01', 50]]);
  });

  it('GB B-0012: 50% alert in GB_2026-08, voided in GB_2026-09 → spend $0, alert remains', async () => {
    expect(await spendCents(t.db, 'B-0012', '2026-08-04')).toBe(0);
    expect((await alertsFor(t.db, 'B-0012')).map((a) => a.threshold)).toEqual([50]);
  });

  it('top members by % used per group and policy year', async () => {
    const r = await t.db.query<{ group_id: string; start_date: string; member_id: string; spend: number; ded: number }>(
      `WITH s AS (
         SELECT m.group_id, py.id AS py_id, py.start_date, m.id AS member_id,
                COALESCE(sum(cc.effective_amount_cents), 0)::bigint AS spend,
                COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS ded
         FROM member m JOIN policy_year py ON py.group_id = m.group_id
         LEFT JOIN laser l ON l.member_id = m.id AND l.policy_year_id = py.id
         LEFT JOIN claim_current cc ON cc.member_id = m.id AND cc.policy_year_id = py.id
         WHERE m.group_id IN ('GA', 'GB')
         GROUP BY m.group_id, py.id, py.start_date, m.id, l.specific_deductible_cents, py.specific_deductible_cents),
       ranked AS (SELECT *, row_number() OVER (PARTITION BY py_id ORDER BY spend::numeric / ded DESC, member_id) AS rn FROM s)
       SELECT group_id, start_date, member_id, spend, ded FROM ranked WHERE rn <= 3 ORDER BY start_date, group_id, rn`,
    );
    expect(r.rows.map((x) => [x.group_id, x.start_date, x.member_id, x.spend])).toEqual([
      ['GB', '2025-07-01', 'B-0006', 6_000_000],
      ['GB', '2025-07-01', 'B-0002', 5_800_000],
      ['GB', '2025-07-01', 'B-0200', 1_845_323],
      ['GA', '2026-01-01', 'A-0005', 5_600_000],
      ['GA', '2026-01-01', 'A-0003', 5_100_000],
      ['GA', '2026-01-01', 'A-0077', 3_875_277],
      ['GB', '2026-07-01', 'B-0010', 5_800_000],
      ['GB', '2026-07-01', 'B-0008', 4_500_000],
      ['GB', '2026-07-01', 'B-0171', 1_464_554],
    ]);
  });
});
