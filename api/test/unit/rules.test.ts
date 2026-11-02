import { describe, expect, it } from 'vitest';
import { newAlertThresholds, reachedThresholds, reachesThreshold } from '../../src/domain/alerts.js';
import {
  conflictReason,
  decideVersion,
  recordVersion,
  type ClaimHistory,
  type StoredVersion,
} from '../../src/domain/versions.js';

const v = (version: number, over: Partial<StoredVersion> = {}): StoredVersion => ({
  version,
  memberId: 'A-0013',
  serviceDate: '2026-03-09',
  paidDate: '2026-03-27',
  amountCents: 900_000,
  status: 'paid',
  diagnosisCode: 'C50.911',
  ...over,
});
const history = (...versions: StoredVersion[]): ClaimHistory => {
  const m = new Map<string, ClaimHistory>();
  for (const x of versions) recordVersion(m, 'c', x);
  return m.get('c')!;
};

describe('decideVersion', () => {
  it('a claim never seen before is new', () => {
    expect(decideVersion(undefined, v(0))).toEqual({ outcome: 'new' });
  });

  it('a higher version is new even with a gap (v0 → v2)', () => {
    expect(decideVersion(history(v(0)), v(2, { amountCents: 1_800_000 }))).toEqual({ outcome: 'new' });
  });

  it('a version lower than the current one is older_ignored (claim 101991: v1 arrives after v2)', () => {
    expect(decideVersion(history(v(0), v(2, { amountCents: 1_800_000 })), v(1, { amountCents: 2_500_000 }))).toEqual({
      outcome: 'older_ignored',
    });
  });

  it('the same version with identical values is unchanged', () => {
    expect(decideVersion(history(v(0)), v(0))).toEqual({ outcome: 'unchanged' });
    // including an older version that was itself stored as older_ignored
    expect(decideVersion(history(v(0), v(2), v(1)), v(1))).toEqual({ outcome: 'unchanged' });
  });

  it('the same version with a different value is a conflict listing every difference', () => {
    const d = decideVersion(history(v(0, { amountCents: 47_899 })), v(0, { amountCents: 57_899 }));
    expect(d).toEqual({ outcome: 'conflict', differences: ['paid amount 478.99 → 578.99'] });

    const all = decideVersion(
      history(v(0)),
      v(0, {
        memberId: 'A-0014',
        serviceDate: '2026-03-10',
        paidDate: null,
        amountCents: 1,
        status: 'reversed',
        diagnosisCode: null,
      }),
    );
    expect(all.outcome).toBe('conflict');
    if (all.outcome === 'conflict') expect(all.differences).toHaveLength(6);
  });

  it('a conflict on an older version is still a conflict', () => {
    expect(decideVersion(history(v(0), v(3)), v(0, { amountCents: 1 })).outcome).toBe('conflict');
  });

  it('builds a readable conflict reason with the first-loaded location', () => {
    expect(
      conflictReason('101410', 0, { ...v(0), filename: 'GA_2026-07.csv', rowNumber: 22 }, ['paid amount 478.99 → 578.99']),
    ).toBe(
      'Conflict: claim 101410 version 0 was already loaded with different values (first loaded from GA_2026-07.csv row 22): paid amount 478.99 → 578.99. The first-loaded values were kept',
    );
  });

  it('recordVersion keeps the highest version as current', () => {
    const h = history(v(2), v(0), v(1));
    expect(h.currentVersion).toBe(2);
    expect([...h.versions.keys()].sort()).toEqual([0, 1, 2]);
  });
});

describe('alert thresholds', () => {
  const DED = 5_000_000; // $50,000

  it('exactly 50% fires', () => {
    expect(reachesThreshold(2_500_000, DED, 50)).toBe(true);
    expect(reachedThresholds(2_500_000, DED)).toEqual([50]);
  });

  it('49.99% does not fire (one cent short of 50%)', () => {
    expect(reachesThreshold(2_499_999, DED, 50)).toBe(false);
    expect(reachedThresholds(2_499_500, DED)).toEqual([]);
  });

  it('exactly 75% and exactly 100% fire', () => {
    expect(reachedThresholds(3_750_000, DED)).toEqual([50, 75]);
    expect(reachedThresholds(5_000_000, DED)).toEqual([50, 75, 100]);
    expect(reachedThresholds(4_999_999, DED)).toEqual([50, 75]);
  });

  it('one file crossing 50, 75 and 100 raises all three', () => {
    // A-0005: a single $56,000 claim against $50,000
    expect(newAlertThresholds(5_600_000, DED, [])).toEqual([50, 75, 100]);
  });

  it('thresholds already alerted are not raised again (drop then rise)', () => {
    expect(newAlertThresholds(5_600_000, DED, [50, 75, 100])).toEqual([]);
    expect(newAlertThresholds(4_000_000, DED, [50])).toEqual([75]);
  });

  it('uses the laser deductible when given (A-0007: $65,000 of $120,000 is 54%)', () => {
    expect(reachedThresholds(6_500_000, 12_000_000)).toEqual([50]);
  });

  it('works with odd deductibles without rounding errors', () => {
    // 1/3 of a cent short: 50% of 3 cents is 1.5 cents → 1 cent is short, 2 cents reaches
    expect(reachesThreshold(1, 3, 50)).toBe(false);
    expect(reachesThreshold(2, 3, 50)).toBe(true);
    expect(reachesThreshold(Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER, 100)).toBe(true);
  });
});
