import { Router } from 'express';
import { currentUser, type AppUser } from '../auth.js';
import type { Db } from '../db.js';
import { HttpError } from '../http.js';
import { centsToDollars, excessCents, pctUsed } from '../domain/money.js';
import { scopeParams, VISIBLE_GROUPS } from '../scope.js';

export interface PolicyYearRow {
  id: number;
  startDate: string;
  endDate: string;
  deductibleCents: number;
}

/** The policy year containing `today`, else the latest one (by start date); null if the group has none. */
export function currentPolicyYearId(years: PolicyYearRow[], today: string): number | null {
  const containing = years.find((y) => y.startDate <= today && today <= y.endDate);
  if (containing) return containing.id;
  let latest: PolicyYearRow | null = null;
  for (const y of years) if (!latest || y.startDate > latest.startDate) latest = y;
  return latest?.id ?? null;
}

const policyYearJson = (y: PolicyYearRow) => ({
  id: y.id,
  startDate: y.startDate,
  endDate: y.endDate,
  deductible: centsToDollars(y.deductibleCents),
});

/** Plain code-unit order, so ids sort the same everywhere (no locale collation). */
export const byCodeUnit = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

interface GroupRow {
  id: string;
  name: string;
  brokerage_id: string | null;
  brokerage_name: string | null;
  member_count: number;
  open_alert_count: number;
  policy_years: PolicyYearRow[];
}

/** Visible groups (all for analysts, own brokerage's for brokers), optionally just one. */
async function visibleGroups(db: Db, user: AppUser, groupId: string | null): Promise<GroupRow[]> {
  const r = await db.query<GroupRow>(
    `WITH ${VISIBLE_GROUPS}
     SELECT g.id, g.name, b.id AS brokerage_id, b.name AS brokerage_name,
            (SELECT count(*) FROM member m WHERE m.group_id = g.id)::int AS member_count,
            (SELECT count(*) FROM alert a WHERE a.group_id = g.id AND a.status = 'open')::int AS open_alert_count,
            COALESCE((SELECT json_agg(json_build_object('id', py.id, 'startDate', py.start_date,
                                                        'endDate', py.end_date,
                                                        'deductibleCents', py.specific_deductible_cents)
                                      ORDER BY py.start_date)
                      FROM policy_year py WHERE py.group_id = g.id), '[]'::json) AS policy_years
     FROM visible_group g LEFT JOIN brokerage b ON b.id = g.brokerage_id
     WHERE $3::text IS NULL OR g.id = $3
     ORDER BY g.id`,
    [...scopeParams(user), groupId],
  );
  return r.rows;
}

export function groupRoutes(db: Db, today: () => string): Router {
  const router = Router();

  router.get('/me', (_req, res) => {
    const u = currentUser(res);
    res.json({ email: u.email, role: u.role, brokerage: u.brokerage });
  });

  router.get('/groups', async (_req, res) => {
    const groups = await visibleGroups(db, currentUser(res), null);
    const now = today();
    res.json(
      groups.map((g) => ({
        id: g.id,
        name: g.name,
        brokerage: g.brokerage_id ? { id: g.brokerage_id, name: g.brokerage_name } : null,
        memberCount: g.member_count,
        openAlertCount: g.open_alert_count,
        policyYears: g.policy_years.map(policyYearJson),
        currentPolicyYearId: currentPolicyYearId(g.policy_years, now),
      })),
    );
  });

  router.get('/groups/:groupId/spend', async (req, res) => {
    const user = currentUser(res);
    const group = (await visibleGroups(db, user, req.params.groupId))[0];
    if (!group) throw new HttpError(404, 'Group not found');

    let year: PolicyYearRow | undefined;
    const requested = req.query.policyYearId;
    if (requested !== undefined) {
      if (typeof requested !== 'string' || !/^[1-9]\d{0,9}$/.test(requested)) {
        throw new HttpError(400, 'policyYearId must be a positive integer');
      }
      year = group.policy_years.find((y) => y.id === Number(requested));
      if (!year) throw new HttpError(404, 'Policy year not found for this group');
    } else {
      const id = currentPolicyYearId(group.policy_years, today());
      year = group.policy_years.find((y) => y.id === id);
      if (!year) throw new HttpError(404, 'This group has no policy years');
    }

    // Scoped again in SQL (not only by the lookup above): members are reached through visible_group.
    const r = await db.query<{
      member_id: string;
      first_name: string;
      last_name: string;
      relationship: string | null;
      spend_cents: number;
      claim_count: number;
      deductible_cents: number;
      has_laser: boolean;
      thresholds: number[];
    }>(
      `WITH ${VISIBLE_GROUPS},
       spend AS (
         SELECT cc.member_id, sum(cc.effective_amount_cents)::bigint AS spend_cents, count(*)::int AS claim_count
         FROM claim_current cc WHERE cc.policy_year_id = $4 GROUP BY cc.member_id)
       SELECT m.id AS member_id, m.first_name, m.last_name, m.relationship,
              COALESCE(s.spend_cents, 0)::bigint AS spend_cents, COALESCE(s.claim_count, 0) AS claim_count,
              COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS deductible_cents,
              (l.member_id IS NOT NULL) AS has_laser,
              ARRAY(SELECT a.threshold FROM alert a
                    WHERE a.member_id = m.id AND a.policy_year_id = py.id ORDER BY a.threshold) AS thresholds
       FROM member m
       JOIN visible_group g ON g.id = m.group_id
       JOIN policy_year py ON py.id = $4 AND py.group_id = m.group_id
       LEFT JOIN laser l ON l.member_id = m.id AND l.policy_year_id = py.id
       LEFT JOIN spend s ON s.member_id = m.id
       WHERE m.group_id = $3`,
      [...scopeParams(user), group.id, year.id],
    );

    const members = r.rows.map((m) => ({
      memberId: m.member_id,
      firstName: m.first_name,
      lastName: m.last_name,
      relationship: m.relationship,
      spend: centsToDollars(m.spend_cents),
      deductible: centsToDollars(m.deductible_cents),
      hasLaser: m.has_laser,
      pctUsed: pctUsed(m.spend_cents, m.deductible_cents),
      excess: centsToDollars(excessCents(m.spend_cents, m.deductible_cents)),
      claimCount: m.claim_count,
      alertThresholds: m.thresholds,
    }));
    members.sort((a, b) => b.pctUsed - a.pctUsed || byCodeUnit(a.memberId, b.memberId));

    res.json({ group: { id: group.id, name: group.name }, policyYear: policyYearJson(year), members });
  });

  router.get('/members/:memberId', async (req, res) => {
    const user = currentUser(res);
    const params = [...scopeParams(user), req.params.memberId] as const;

    const mr = await db.query<{
      id: string;
      group_id: string;
      first_name: string;
      last_name: string;
      date_of_birth: string | null;
      relationship: string | null;
    }>(
      `WITH ${VISIBLE_GROUPS}
       SELECT m.id, m.group_id, m.first_name, m.last_name, m.date_of_birth, m.relationship
       FROM member m JOIN visible_group g ON g.id = m.group_id
       WHERE m.id = $3`,
      [...params],
    );
    const member = mr.rows[0];
    if (!member) throw new HttpError(404, 'Member not found');

    const years = await db.query<{
      id: number;
      start_date: string;
      end_date: string;
      deductible_cents: number;
      has_laser: boolean;
      spend_cents: number;
    }>(
      `WITH ${VISIBLE_GROUPS}
       SELECT py.id, py.start_date, py.end_date,
              COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS deductible_cents,
              (l.member_id IS NOT NULL) AS has_laser,
              COALESCE((SELECT sum(cc.effective_amount_cents) FROM claim_current cc
                        WHERE cc.member_id = m.id AND cc.policy_year_id = py.id), 0)::bigint AS spend_cents
       FROM member m
       JOIN visible_group g ON g.id = m.group_id
       JOIN policy_year py ON py.group_id = m.group_id
       LEFT JOIN laser l ON l.member_id = m.id AND l.policy_year_id = py.id
       WHERE m.id = $3
       ORDER BY py.start_date`,
      [...params],
    );

    interface VersionJson {
      version: number;
      memberId: string;
      status: 'paid' | 'reversed';
      paidAmountCents: number;
      serviceDate: string;
      paidDate: string | null;
      diagnosisCode: string | null;
      filename: string;
      rowNumber: number;
      loadedAt: string;
      isCurrent: boolean;
    }
    // Claims whose current version belongs to this member, each with its full version history.
    const claims = await db.query<{
      claim_id: string;
      policy_year_id: number | null;
      current_version: number;
      status: 'paid' | 'reversed';
      effective_amount_cents: number;
      service_date: string;
      versions: VersionJson[];
    }>(
      `WITH ${VISIBLE_GROUPS}
       SELECT cc.claim_id, cc.policy_year_id, cc.version AS current_version, cur.status, cc.effective_amount_cents,
              cc.service_date,
              (SELECT json_agg(json_build_object(
                        'version', v.version, 'memberId', v.member_id, 'status', v.status,
                        'paidAmountCents', v.paid_amount_cents, 'serviceDate', v.service_date,
                        'paidDate', v.paid_date, 'diagnosisCode', v.diagnosis_code, 'filename', u.filename,
                        'rowNumber', v.row_number,
                        'loadedAt', to_char(v.loaded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                        'isCurrent', v.id = cc.claim_version_id)
                      ORDER BY v.version DESC)
               FROM claim_version v JOIN upload u ON u.id = v.upload_id
               WHERE v.group_id = cc.group_id AND v.claim_id = cc.claim_id) AS versions
       FROM claim_current cc
       JOIN visible_group g ON g.id = cc.group_id
       JOIN claim_version cur ON cur.id = cc.claim_version_id
       WHERE cc.member_id = $3
       ORDER BY cc.service_date DESC, cc.claim_id`,
      [...params],
    );

    res.json({
      member: {
        memberId: member.id,
        groupId: member.group_id,
        firstName: member.first_name,
        lastName: member.last_name,
        dateOfBirth: member.date_of_birth,
        relationship: member.relationship,
      },
      policyYears: years.rows.map((y) => ({
        policyYearId: y.id,
        startDate: y.start_date,
        endDate: y.end_date,
        deductible: centsToDollars(y.deductible_cents),
        hasLaser: y.has_laser,
        spend: centsToDollars(y.spend_cents),
        pctUsed: pctUsed(y.spend_cents, y.deductible_cents),
        excess: centsToDollars(excessCents(y.spend_cents, y.deductible_cents)),
      })),
      claims: claims.rows.map((c) => ({
        claimId: c.claim_id,
        policyYearId: c.policy_year_id,
        currentVersion: c.current_version,
        status: c.status,
        effectiveAmount: centsToDollars(c.effective_amount_cents),
        serviceDate: c.service_date,
        versions: c.versions.map(({ paidAmountCents, ...v }) => ({ ...v, paidAmount: centsToDollars(paidAmountCents) })),
      })),
    });
  });

  return router;
}
