import path from 'node:path';
import { runner } from 'node-pg-migrate';
import { createPool, withTransaction, type Db } from '../../src/db.js';
import { ingestClaimFile } from '../../src/services/ingest.js';
import { loadReferenceData, seedReferenceData } from '../../src/services/referenceData.js';

export const API_DIR = path.resolve(import.meta.dirname, '../..');
export const DATA_DIR = path.resolve(API_DIR, '../data');
export const CLAIMS_DIR = path.join(DATA_DIR, 'claims');

export interface TestDb {
  db: Db;
  schema: string;
  /** Remove all uploads/claims/alerts but keep the seeded reference data. */
  resetClaims(): Promise<void>;
  close(): Promise<void>;
}

/**
 * A fresh Postgres schema for one test file: drop it if a previous run left it behind, run the real migrations
 * into it, then seed the reference data with the real seed code. Each test file uses its own schema name, so
 * files can run in parallel against the same database.
 */
export async function setupTestDb(name: string): Promise<TestDb> {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error('DATABASE_URL must point at a disposable Postgres database to run integration tests');
  const schema = `test_${name.toLowerCase().replace(/[^a-z0-9_]/g, '_')}`;

  const admin = createPool(url, { max: 1 });
  await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
  await admin.end();

  await runner({
    databaseUrl: url,
    dir: path.join(API_DIR, 'migrations'),
    direction: 'up',
    migrationsTable: 'pgmigrations',
    schema,
    createSchema: true,
    checkOrder: true,
    verbose: false,
    log: () => undefined,
  });

  const db = createPool(url, { schema, max: 5 });
  await withTransaction(db, (c) => seedReferenceData(c, loadReferenceData(DATA_DIR)));

  return {
    db,
    schema,
    async resetClaims() {
      await db.query('TRUNCATE alert, claim_current, claim_version, upload_row_error, upload RESTART IDENTITY CASCADE');
    },
    async close() {
      await db.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
      await db.end();
    },
  };
}

/** Ingest a CSV given as text lines (joined with CRLF like the real files). */
export function ingestText(db: Db, filename: string, lines: string[], uploadedBy = 'test') {
  return ingestClaimFile(db, { filename, content: Buffer.from(lines.join('\r\n') + '\r\n'), uploadedBy });
}

export const GA_HEADER = 'Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount,Status,Diagnosis Code';
export const GB_HEADER = 'claim_number,version,member,dos,paid_dt,amount,claim_status';

/** Spend (cents) of one member in the policy year containing `onDate`. */
export async function spendCents(db: Db, memberId: string, onDate: string): Promise<number> {
  const r = await db.query<{ spend: number }>(
    `SELECT COALESCE(sum(cc.effective_amount_cents), 0)::bigint AS spend
     FROM claim_current cc JOIN policy_year py ON py.id = cc.policy_year_id
     WHERE cc.member_id = $1 AND $2::date BETWEEN py.start_date AND py.end_date`,
    [memberId, onDate],
  );
  return r.rows[0]!.spend;
}

/** Every (member, policy year) spend, for "nothing changed" comparisons. */
export async function allSpend(db: Db): Promise<Record<string, number>> {
  const r = await db.query<{ member_id: string; policy_year_id: number; spend: number }>(
    `SELECT member_id, policy_year_id, sum(effective_amount_cents)::bigint AS spend
     FROM claim_current WHERE policy_year_id IS NOT NULL GROUP BY 1, 2 ORDER BY 1, 2`,
  );
  return Object.fromEntries(r.rows.map((x) => [`${x.member_id}|${x.policy_year_id}`, x.spend]));
}

export async function alertsFor(db: Db, memberId: string) {
  const r = await db.query<{ threshold: number; status: string; spend_cents_at_trigger: number; deductible_cents_at_trigger: number; start_date: string }>(
    `SELECT a.threshold, a.status, a.spend_cents_at_trigger, a.deductible_cents_at_trigger, py.start_date
     FROM alert a JOIN policy_year py ON py.id = a.policy_year_id
     WHERE a.member_id = $1 ORDER BY py.start_date, a.threshold`,
    [memberId],
  );
  return r.rows;
}

export async function countRows(db: Db, table: 'alert' | 'claim_version' | 'claim_current' | 'upload'): Promise<number> {
  const r = await db.query<{ n: number }>(`SELECT count(*)::bigint AS n FROM ${table}`);
  return r.rows[0]!.n;
}
