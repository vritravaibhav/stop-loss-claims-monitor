import { createHash } from 'node:crypto';
import type pg from 'pg';
import { newAlertThresholds } from '../domain/alerts.js';
import { parseClaimFile, type ClaimRow } from '../domain/claimFile.js';
import { centsToDollars, pctUsed } from '../domain/money.js';
import {
  conflictReason,
  decideVersion,
  recordVersion,
  type ClaimHistory,
  type StoredVersion,
} from '../domain/versions.js';
import { withTransaction, type Db } from '../db.js';
import type { UploadResult } from './uploads.js';

export interface IngestInput {
  filename: string;
  content: Buffer;
  uploadedBy: string;
}

interface RowError {
  rowNumber: number;
  reason: string;
  raw: string;
}

interface Counts {
  rows: number;
  applied: number;
  unchanged: number;
  olderIgnored: number;
  summary: number;
  errors: number;
}

/** Strip any client-side path (`C:\fakepath\GA_2026-04.csv`, `dir/GA.csv`) and keep the bare file name. */
export function baseFilename(name: string): string {
  const parts = name.split(/[\\/]/);
  return (parts[parts.length - 1] ?? '').trim() || 'unnamed.csv';
}

/** Group id = file-name prefix before the first `_` (`GA_2026-04_resend.csv` → `GA`). */
export function groupPrefixFromFilename(filename: string): string | null {
  const m = /^([A-Za-z0-9-]+)_/.exec(filename);
  return m ? m[1]! : null;
}

const emptyCounts = (): Counts => ({ rows: 0, applied: 0, unchanged: 0, olderIgnored: 0, summary: 0, errors: 0 });

/**
 * Load one claim file. Everything for the file happens in one transaction that holds an advisory lock on the
 * group, so concurrent uploads for the same group serialise. Steps:
 *  1. parse (pure) → claim rows / row errors / summary rows;
 *  2. validate members, decide each row against the stored versions (in file order, so duplicates inside the
 *     file are handled the same as across files);
 *  3. insert the new versions (current or older) and recompute `claim_current` for claims that got a newer
 *     version;
 *  4. for every (member, policy year) touched — the new placement *and* the old one if a newer version moved
 *     the claim — compute spend and raise alerts for thresholds reached for the first time.
 * Rejected files (unknown group prefix, unrecognised header) are recorded with status `rejected`.
 */
export async function ingestClaimFile(db: Db, input: IngestInput): Promise<UploadResult> {
  const filename = baseFilename(input.filename);
  const sha256 = createHash('sha256').update(input.content).digest('hex');
  const parsed = parseClaimFile(input.content);
  const prefix = groupPrefixFromFilename(filename);

  return withTransaction(db, async (client) => {
    let groupId: string | null = null;
    if (prefix) {
      const g = await client.query<{ id: string }>('SELECT id FROM employer_group WHERE upper(id) = upper($1)', [prefix]);
      groupId = g.rows[0]?.id ?? null;
    }

    const base = { filename, sha256, uploadedBy: input.uploadedBy, sizeBytes: input.content.length };
    if (!groupId) {
      const reason = prefix
        ? `Unknown group "${prefix}" (taken from the file name prefix before the first "_")`
        : 'File name must start with the group ID followed by "_" (for example GA_2026-04.csv)';
      return insertRejected(client, { ...base, groupId: null, reason });
    }

    // Serialise every upload for this group (released automatically at COMMIT/ROLLBACK).
    await client.query(`SELECT pg_advisory_xact_lock(hashtext('stoploss.ingest'), hashtext($1))`, [groupId]);

    if (!parsed.ok) return insertRejected(client, { ...base, groupId, reason: capitalise(parsed.reason) });

    const up = await client.query<{ id: number; uploaded_at: Date }>(
      `INSERT INTO upload (group_id, filename, sha256, size_bytes, layout, status, uploaded_by)
       VALUES ($1, $2, $3, $4, $5, 'processed', $6) RETURNING id, uploaded_at`,
      [groupId, filename, sha256, base.sizeBytes, parsed.layout.id, input.uploadedBy],
    );
    const uploadId = up.rows[0]!.id;
    const uploadedAt = up.rows[0]!.uploaded_at;

    const claimLines = parsed.lines.flatMap((l) => (l.kind === 'claim' ? [l] : []));

    // Members referenced by the file → their group (unknown ids are simply absent).
    const memberGroup = new Map<string, string>();
    const memberIds = [...new Set(claimLines.map((l) => l.claim.memberId))];
    if (memberIds.length > 0) {
      const res = await client.query<{ id: string; group_id: string }>(
        'SELECT id, group_id FROM member WHERE id = ANY($1::text[])',
        [memberIds],
      );
      for (const r of res.rows) memberGroup.set(r.id, r.group_id);
    }

    // Everything already stored for the claims in this file.
    const histories = new Map<string, ClaimHistory>();
    const claimIds = [...new Set(claimLines.map((l) => l.claim.claimId))];
    if (claimIds.length > 0) {
      const res = await client.query<{
        claim_id: string;
        version: number;
        member_id: string;
        service_date: string;
        paid_date: string | null;
        paid_amount_cents: number;
        status: 'paid' | 'reversed';
        diagnosis_code: string | null;
        row_number: number;
        filename: string;
      }>(
        `SELECT cv.claim_id, cv.version, cv.member_id, cv.service_date, cv.paid_date, cv.paid_amount_cents, cv.status,
                cv.diagnosis_code, cv.row_number, u.filename
         FROM claim_version cv JOIN upload u ON u.id = cv.upload_id
         WHERE cv.group_id = $1 AND cv.claim_id = ANY($2::text[])`,
        [groupId, claimIds],
      );
      for (const r of res.rows) {
        recordVersion(histories, r.claim_id, {
          version: r.version,
          memberId: r.member_id,
          serviceDate: r.service_date,
          paidDate: r.paid_date,
          amountCents: r.paid_amount_cents,
          status: r.status,
          diagnosisCode: r.diagnosis_code,
          filename: r.filename,
          rowNumber: r.row_number,
        });
      }
    }

    // Decide every row in file order.
    const counts = emptyCounts();
    const errors: RowError[] = [];
    const toInsert: (ClaimRow & { rowNumber: number })[] = [];
    const newerClaims = new Set<string>();

    for (const line of parsed.lines) {
      counts.rows++;
      if (line.kind === 'summary') {
        counts.summary++;
        continue;
      }
      if (line.kind === 'error') {
        errors.push({ rowNumber: line.rowNumber, reason: line.reason, raw: line.raw });
        continue;
      }
      const c = line.claim;
      const owner = memberGroup.get(c.memberId);
      if (owner === undefined) {
        errors.push({ rowNumber: line.rowNumber, reason: `Unknown member "${c.memberId}"`, raw: line.raw });
        continue;
      }
      if (owner !== groupId) {
        errors.push({
          rowNumber: line.rowNumber,
          reason: `Member "${c.memberId}" belongs to group ${owner}, not ${groupId}`,
          raw: line.raw,
        });
        continue;
      }

      const history = histories.get(c.claimId);
      const decision = decideVersion(history, c);
      const stored: StoredVersion = { ...c, filename, rowNumber: line.rowNumber };
      switch (decision.outcome) {
        case 'new':
          counts.applied++;
          toInsert.push({ ...c, rowNumber: line.rowNumber });
          recordVersion(histories, c.claimId, stored);
          newerClaims.add(c.claimId);
          break;
        case 'older_ignored':
          counts.olderIgnored++;
          toInsert.push({ ...c, rowNumber: line.rowNumber });
          recordVersion(histories, c.claimId, stored);
          break;
        case 'unchanged':
          counts.unchanged++;
          break;
        case 'conflict':
          errors.push({
            rowNumber: line.rowNumber,
            reason: conflictReason(c.claimId, c.version, history!.versions.get(c.version)!, decision.differences),
            raw: line.raw,
          });
          break;
      }
    }
    counts.errors = errors.length;

    if (toInsert.length > 0) {
      await client.query(
        `INSERT INTO claim_version (group_id, claim_id, version, member_id, service_date, paid_date, paid_amount_cents,
                                    status, diagnosis_code, upload_id, row_number)
         SELECT $1, t.claim_id, t.version, t.member_id, t.service_date, t.paid_date, t.amount, t.status, t.diagnosis,
                $2, t.row_number
         FROM unnest($3::text[], $4::int[], $5::text[], $6::date[], $7::date[], $8::bigint[], $9::text[], $10::text[],
                     $11::int[])
           AS t(claim_id, version, member_id, service_date, paid_date, amount, status, diagnosis, row_number)`,
        [
          groupId,
          uploadId,
          toInsert.map((r) => r.claimId),
          toInsert.map((r) => r.version),
          toInsert.map((r) => r.memberId),
          toInsert.map((r) => r.serviceDate),
          toInsert.map((r) => r.paidDate),
          toInsert.map((r) => r.amountCents),
          toInsert.map((r) => r.status),
          toInsert.map((r) => r.diagnosisCode),
          toInsert.map((r) => r.rowNumber),
        ],
      );
    }

    // (member, policy year) pairs whose spend may have changed.
    const touched = new Map<string, { memberId: string; policyYearId: number }>();
    const touch = (memberId: string, policyYearId: number | null) => {
      if (policyYearId !== null) touched.set(`${memberId}|${policyYearId}`, { memberId, policyYearId });
    };

    if (newerClaims.size > 0) {
      const changed = [...newerClaims];
      // Where these claims counted before this file (a newer version may move a claim to another member or year).
      const before = await client.query<{ member_id: string; policy_year_id: number | null }>(
        'SELECT member_id, policy_year_id FROM claim_current WHERE group_id = $1 AND claim_id = ANY($2::text[])',
        [groupId, changed],
      );
      for (const r of before.rows) touch(r.member_id, r.policy_year_id);

      // Current version = highest stored version; policy year by service date; reversed → 0.
      const after = await client.query<{ member_id: string; policy_year_id: number | null }>(
        `INSERT INTO claim_current (group_id, claim_id, claim_version_id, version, member_id, service_date,
                                    policy_year_id, effective_amount_cents)
         SELECT DISTINCT ON (cv.claim_id)
                cv.group_id, cv.claim_id, cv.id, cv.version, cv.member_id, cv.service_date, py.id,
                CASE WHEN cv.status = 'reversed' THEN 0 ELSE cv.paid_amount_cents END
         FROM claim_version cv
         LEFT JOIN policy_year py
           ON py.group_id = cv.group_id AND cv.service_date BETWEEN py.start_date AND py.end_date
         WHERE cv.group_id = $1 AND cv.claim_id = ANY($2::text[])
         ORDER BY cv.claim_id, cv.version DESC
         ON CONFLICT (group_id, claim_id) DO UPDATE SET
           claim_version_id = EXCLUDED.claim_version_id,
           version = EXCLUDED.version,
           member_id = EXCLUDED.member_id,
           service_date = EXCLUDED.service_date,
           policy_year_id = EXCLUDED.policy_year_id,
           effective_amount_cents = EXCLUDED.effective_amount_cents
         RETURNING member_id, policy_year_id`,
        [groupId, changed],
      );
      for (const r of after.rows) touch(r.member_id, r.policy_year_id);
    }

    const alertsRaised: UploadResult['alertsRaised'] = [];
    if (touched.size > 0) {
      const pairs = [...touched.values()];
      const spend = await client.query<{
        member_id: string;
        policy_year_id: number;
        spend_cents: number;
        deductible_cents: number;
        alerted: number[];
      }>(
        `SELECT p.member_id, p.policy_year_id,
                COALESCE((SELECT sum(cc.effective_amount_cents) FROM claim_current cc
                          WHERE cc.member_id = p.member_id AND cc.policy_year_id = p.policy_year_id), 0)::bigint
                  AS spend_cents,
                COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS deductible_cents,
                ARRAY(SELECT a.threshold FROM alert a
                      WHERE a.member_id = p.member_id AND a.policy_year_id = p.policy_year_id) AS alerted
         FROM unnest($1::text[], $2::int[]) AS p(member_id, policy_year_id)
         JOIN policy_year py ON py.id = p.policy_year_id
         LEFT JOIN laser l ON l.member_id = p.member_id AND l.policy_year_id = p.policy_year_id`,
        [pairs.map((p) => p.memberId), pairs.map((p) => p.policyYearId)],
      );

      const candidates: { memberId: string; policyYearId: number; threshold: number; spend: number; ded: number }[] = [];
      for (const r of spend.rows) {
        for (const t of newAlertThresholds(r.spend_cents, r.deductible_cents, r.alerted)) {
          candidates.push({
            memberId: r.member_id,
            policyYearId: r.policy_year_id,
            threshold: t,
            spend: r.spend_cents,
            ded: r.deductible_cents,
          });
        }
      }
      if (candidates.length > 0) {
        // The unique (member, policy year, threshold) constraint is the real guard against duplicates.
        const inserted = await client.query<{
          id: number;
          member_id: string;
          threshold: number;
          spend_cents_at_trigger: number;
          deductible_cents_at_trigger: number;
        }>(
          `INSERT INTO alert (group_id, member_id, policy_year_id, threshold, spend_cents_at_trigger,
                              deductible_cents_at_trigger, upload_id)
           SELECT $1, c.member_id, c.policy_year_id, c.threshold, c.spend, c.ded, $2
           FROM unnest($3::text[], $4::int[], $5::int[], $6::bigint[], $7::bigint[])
             AS c(member_id, policy_year_id, threshold, spend, ded)
           ON CONFLICT (member_id, policy_year_id, threshold) DO NOTHING
           RETURNING id, member_id, threshold, spend_cents_at_trigger, deductible_cents_at_trigger`,
          [
            groupId,
            uploadId,
            candidates.map((c) => c.memberId),
            candidates.map((c) => c.policyYearId),
            candidates.map((c) => c.threshold),
            candidates.map((c) => c.spend),
            candidates.map((c) => c.ded),
          ],
        );
        const sorted = [...inserted.rows].sort((a, b) =>
          a.member_id === b.member_id ? a.threshold - b.threshold : a.member_id < b.member_id ? -1 : 1,
        );
        for (const a of sorted) {
          alertsRaised.push({
            id: a.id,
            memberId: a.member_id,
            threshold: a.threshold,
            spend: centsToDollars(a.spend_cents_at_trigger),
            deductible: centsToDollars(a.deductible_cents_at_trigger),
            pctUsed: pctUsed(a.spend_cents_at_trigger, a.deductible_cents_at_trigger),
          });
        }
      }
    }

    if (errors.length > 0) {
      await client.query(
        `INSERT INTO upload_row_error (upload_id, row_number, reason, raw)
         SELECT $1, * FROM unnest($2::int[], $3::text[], $4::text[])`,
        [uploadId, errors.map((e) => e.rowNumber), errors.map((e) => e.reason), errors.map((e) => e.raw)],
      );
    }

    await client.query(
      `UPDATE upload SET row_count = $2, applied_count = $3, unchanged_count = $4, older_ignored_count = $5,
                         summary_count = $6, error_count = $7
       WHERE id = $1`,
      [uploadId, counts.rows, counts.applied, counts.unchanged, counts.olderIgnored, counts.summary, counts.errors],
    );

    return {
      id: uploadId,
      filename,
      groupId,
      layout: parsed.layout.id,
      status: 'processed',
      rejectionReason: null,
      uploadedBy: input.uploadedBy,
      uploadedAt: uploadedAt.toISOString(),
      sha256,
      counts,
      errors,
      alertsRaised,
    } satisfies UploadResult;
  });
}

async function insertRejected(
  client: pg.PoolClient,
  r: { filename: string; sha256: string; uploadedBy: string; sizeBytes: number; groupId: string | null; reason: string },
): Promise<UploadResult> {
  const res = await client.query<{ id: number; uploaded_at: Date }>(
    `INSERT INTO upload (group_id, filename, sha256, size_bytes, layout, status, rejection_reason, uploaded_by)
     VALUES ($1, $2, $3, $4, NULL, 'rejected', $5, $6) RETURNING id, uploaded_at`,
    [r.groupId, r.filename, r.sha256, r.sizeBytes, r.reason, r.uploadedBy],
  );
  return {
    id: res.rows[0]!.id,
    filename: r.filename,
    groupId: r.groupId,
    layout: null,
    status: 'rejected',
    rejectionReason: r.reason,
    uploadedBy: r.uploadedBy,
    uploadedAt: res.rows[0]!.uploaded_at.toISOString(),
    sha256: r.sha256,
    counts: emptyCounts(),
    errors: [],
    alertsRaised: [],
  };
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
