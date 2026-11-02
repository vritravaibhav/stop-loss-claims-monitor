import { readFileSync } from 'node:fs';
import path from 'node:path';
import type pg from 'pg';
import { decodeText, parseCsvLine, splitLines } from '../domain/csv.js';
import { parseDate } from '../domain/dates.js';
import { normaliseHeader } from '../domain/layouts.js';
import { parseAmountToCents } from '../domain/money.js';

/** Brokerages, group assignments and app users seeded alongside the reference CSVs (SPEC "Seed logins"). */
export const SEED_BROKERAGES = [
  { id: 'brokerage-a', name: 'Brokerage A', groups: ['GA'] },
  { id: 'brokerage-b', name: 'Brokerage B', groups: ['GB'] },
] as const;

export const SEED_USERS = [
  { email: 'analyst@example.test', role: 'analyst', brokerageId: null },
  { email: 'broker.a@example.test', role: 'broker', brokerageId: 'brokerage-a' },
  { email: 'broker.b@example.test', role: 'broker', brokerageId: 'brokerage-b' },
] as const;

export interface ReferenceData {
  groups: { id: string; name: string }[];
  policyYears: { groupId: string; startDate: string; endDate: string; deductibleCents: number }[];
  members: {
    id: string;
    groupId: string;
    firstName: string;
    lastName: string;
    dateOfBirth: string | null;
    relationship: string | null;
  }[];
  lasers: { groupId: string; memberId: string; policyStart: string; deductibleCents: number }[];
}

/** Read a small reference CSV into records keyed by normalised header name. Throws with file:line on bad input. */
function readCsv(file: string): { line: number; get: (col: string) => string }[] {
  const lines = splitLines(decodeText(readFileSync(file)));
  const first = lines.findIndex((l) => l.trim() !== '');
  if (first === -1) throw new Error(`${file}: empty file`);
  const header = parseCsvLine(lines[first]!);
  if (!header.ok) throw new Error(`${file}:${first + 1}: ${header.reason}`);
  const cols = header.cells.map(normaliseHeader);
  const out: { line: number; get: (col: string) => string }[] = [];
  for (let i = first + 1; i < lines.length; i++) {
    if (lines[i]!.trim() === '') continue;
    const row = parseCsvLine(lines[i]!);
    if (!row.ok) throw new Error(`${file}:${i + 1}: ${row.reason}`);
    const cells = row.cells;
    const lineNo = i + 1;
    out.push({
      line: lineNo,
      get: (col: string) => {
        const idx = cols.indexOf(normaliseHeader(col));
        if (idx === -1) throw new Error(`${file}: missing column ${col}`);
        return (cells[idx] ?? '').trim();
      },
    });
  }
  return out;
}

function dollarsToCents(value: string, where: string): number {
  const r = parseAmountToCents(value);
  if (!r.ok) throw new Error(`${where}: ${r.reason}`);
  return r.cents;
}

function isoOrThrow(value: string, label: string, where: string): string {
  const r = parseDate(value, label);
  if (!r.ok) throw new Error(`${where}: ${r.reason}`);
  return r.date;
}

export function loadReferenceData(dataDir: string): ReferenceData {
  const policiesFile = path.join(dataDir, 'policies.csv');
  const groups = new Map<string, string>();
  const policyYears: ReferenceData['policyYears'] = [];
  for (const r of readCsv(policiesFile)) {
    const where = `${policiesFile}:${r.line}`;
    const groupId = r.get('group_id');
    groups.set(groupId, r.get('group_name'));
    policyYears.push({
      groupId,
      startDate: isoOrThrow(r.get('policy_start'), 'policy start', where),
      endDate: isoOrThrow(r.get('policy_end'), 'policy end', where),
      deductibleCents: dollarsToCents(r.get('specific_deductible'), where),
    });
  }

  const membersFile = path.join(dataDir, 'members.csv');
  const members = readCsv(membersFile).map((r) => {
    const dob = r.get('date_of_birth');
    return {
      id: r.get('member_id'),
      groupId: r.get('group_id'),
      firstName: r.get('first_name'),
      lastName: r.get('last_name'),
      dateOfBirth: dob === '' ? null : isoOrThrow(dob, 'date of birth', `${membersFile}:${r.line}`),
      relationship: r.get('relationship') || null,
    };
  });

  const lasersFile = path.join(dataDir, 'lasers.csv');
  const lasers = readCsv(lasersFile).map((r) => {
    const where = `${lasersFile}:${r.line}`;
    return {
      groupId: r.get('group_id'),
      memberId: r.get('member_id'),
      policyStart: isoOrThrow(r.get('policy_start'), 'policy start', where),
      deductibleCents: dollarsToCents(r.get('specific_deductible'), where),
    };
  });

  return { groups: [...groups].map(([id, name]) => ({ id, name })), policyYears, members, lasers };
}

/** Idempotent upsert of brokerages, groups, policy years, members, lasers and app users. Run inside a transaction. */
export async function seedReferenceData(client: pg.PoolClient, data: ReferenceData): Promise<Record<string, number>> {
  for (const b of SEED_BROKERAGES) {
    await client.query(
      `INSERT INTO brokerage (id, name) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name`,
      [b.id, b.name],
    );
  }
  const brokerageOf = new Map<string, string>(SEED_BROKERAGES.flatMap((b) => b.groups.map((g) => [g, b.id] as const)));

  for (const g of data.groups) {
    await client.query(
      `INSERT INTO employer_group (id, name, brokerage_id) VALUES ($1, $2, $3)
       ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, brokerage_id = EXCLUDED.brokerage_id`,
      [g.id, g.name, brokerageOf.get(g.id) ?? null],
    );
  }

  for (const p of data.policyYears) {
    await client.query(
      `INSERT INTO policy_year (group_id, start_date, end_date, specific_deductible_cents) VALUES ($1, $2, $3, $4)
       ON CONFLICT (group_id, start_date)
       DO UPDATE SET end_date = EXCLUDED.end_date, specific_deductible_cents = EXCLUDED.specific_deductible_cents`,
      [p.groupId, p.startDate, p.endDate, p.deductibleCents],
    );
  }

  const m = data.members;
  await client.query(
    `INSERT INTO member (id, group_id, first_name, last_name, date_of_birth, relationship)
     SELECT * FROM unnest($1::text[], $2::text[], $3::text[], $4::text[], $5::date[], $6::text[])
     ON CONFLICT (id) DO UPDATE SET group_id = EXCLUDED.group_id, first_name = EXCLUDED.first_name,
       last_name = EXCLUDED.last_name, date_of_birth = EXCLUDED.date_of_birth, relationship = EXCLUDED.relationship`,
    [
      m.map((x) => x.id),
      m.map((x) => x.groupId),
      m.map((x) => x.firstName),
      m.map((x) => x.lastName),
      m.map((x) => x.dateOfBirth),
      m.map((x) => x.relationship),
    ],
  );

  for (const l of data.lasers) {
    const res = await client.query<{ policy_year_id: number; member_group: string | null }>(
      `SELECT py.id AS policy_year_id, (SELECT group_id FROM member WHERE id = $2) AS member_group
       FROM policy_year py WHERE py.group_id = $1 AND py.start_date = $3`,
      [l.groupId, l.memberId, l.policyStart],
    );
    const row = res.rows[0];
    if (!row) throw new Error(`laser for ${l.memberId}: group ${l.groupId} has no policy year starting ${l.policyStart}`);
    if (row.member_group !== l.groupId) {
      throw new Error(`laser for ${l.memberId}: member is not in group ${l.groupId}`);
    }
    await client.query(
      `INSERT INTO laser (member_id, policy_year_id, specific_deductible_cents) VALUES ($1, $2, $3)
       ON CONFLICT (member_id, policy_year_id) DO UPDATE SET specific_deductible_cents = EXCLUDED.specific_deductible_cents`,
      [l.memberId, row.policy_year_id, l.deductibleCents],
    );
  }

  for (const u of SEED_USERS) {
    await client.query(
      `INSERT INTO app_user (email, role, brokerage_id) VALUES ($1, $2, $3)
       ON CONFLICT (email) DO UPDATE SET role = EXCLUDED.role, brokerage_id = EXCLUDED.brokerage_id`,
      [u.email, u.role, u.brokerageId],
    );
  }

  return {
    brokerages: SEED_BROKERAGES.length,
    groups: data.groups.length,
    policyYears: data.policyYears.length,
    members: data.members.length,
    lasers: data.lasers.length,
    users: SEED_USERS.length,
  };
}
