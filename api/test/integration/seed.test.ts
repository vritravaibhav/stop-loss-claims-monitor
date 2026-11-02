import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { withTransaction } from '../../src/db.js';
import { loadReferenceData, seedReferenceData } from '../../src/services/referenceData.js';
import { DATA_DIR, setupTestDb, type TestDb } from '../helpers/db.js';

let t: TestDb;
beforeAll(async () => {
  t = await setupTestDb('seed'); // seeds once
});
afterAll(async () => {
  await t?.close();
});

const snapshot = async () => {
  const q = async (sql: string) => (await t.db.query(sql)).rows;
  return {
    brokerages: await q('SELECT * FROM brokerage ORDER BY id'),
    groups: await q('SELECT * FROM employer_group ORDER BY id'),
    policyYears: await q('SELECT * FROM policy_year ORDER BY id'),
    members: (await q('SELECT count(*)::int AS n FROM member'))[0],
    lasers: await q('SELECT * FROM laser ORDER BY member_id'),
    users: await q('SELECT * FROM app_user ORDER BY id'),
  };
};

describe('seed', () => {
  it('is idempotent: running it again (as every start:docker does) changes nothing', async () => {
    const before = await snapshot();
    expect(before.policyYears).toHaveLength(5);
    expect(before.members).toEqual({ n: 1750 });
    await withTransaction(t.db, (c) => seedReferenceData(c, loadReferenceData(DATA_DIR)));
    await withTransaction(t.db, (c) => seedReferenceData(c, loadReferenceData(DATA_DIR)));
    expect(await snapshot()).toEqual(before);
  });

  it('still rejects overlapping policy years, on insert and when an upsert moves the end date', async () => {
    await expect(
      t.db.query(
        `INSERT INTO policy_year (group_id, start_date, end_date, specific_deductible_cents)
         VALUES ('GB', '2026-01-01', '2026-12-31', 100)`,
      ),
    ).rejects.toThrow(/overlaps another policy year of group GB/);
    await expect(
      t.db.query(
        `INSERT INTO policy_year (group_id, start_date, end_date, specific_deductible_cents)
         VALUES ('GB', '2025-07-01', '2026-07-15', 100)
         ON CONFLICT (group_id, start_date) DO UPDATE SET end_date = EXCLUDED.end_date`,
      ),
    ).rejects.toThrow(/overlaps another policy year of group GB/);
  });
});
