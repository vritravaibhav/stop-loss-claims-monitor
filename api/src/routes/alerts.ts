import { Router } from 'express';
import { currentUser, requireAnalyst, type AppUser } from '../auth.js';
import type { Db } from '../db.js';
import { HttpError, parseId } from '../http.js';
import { centsToDollars, pctUsed } from '../domain/money.js';
import { scopeParams, VISIBLE_GROUPS } from '../scope.js';

export const MAX_NOTE_LENGTH = 2000;
const STATUSES = ['open', 'acknowledged', 'all'] as const;
type StatusFilter = (typeof STATUSES)[number];

interface AlertRow {
  id: number;
  group_id: string;
  group_name: string;
  member_id: string;
  member_name: string;
  policy_year_id: number;
  start_date: string;
  end_date: string;
  threshold: number;
  spend_cents_at_trigger: number;
  deductible_cents_at_trigger: number;
  current_spend_cents: number;
  current_deductible_cents: number;
  status: 'open' | 'acknowledged';
  created_at: Date;
  upload_filename: string | null;
  acknowledged_by: string | null;
  acknowledged_at: Date | null;
  ack_note: string | null;
}

function alertJson(a: AlertRow) {
  return {
    id: a.id,
    groupId: a.group_id,
    groupName: a.group_name,
    memberId: a.member_id,
    memberName: a.member_name,
    policyYearId: a.policy_year_id,
    policyYearStart: a.start_date,
    policyYearEnd: a.end_date,
    threshold: a.threshold,
    spendAtTrigger: centsToDollars(a.spend_cents_at_trigger),
    deductibleAtTrigger: centsToDollars(a.deductible_cents_at_trigger),
    currentSpend: centsToDollars(a.current_spend_cents),
    currentPctUsed: pctUsed(a.current_spend_cents, a.current_deductible_cents),
    status: a.status,
    createdAt: a.created_at.toISOString(),
    uploadFilename: a.upload_filename,
    acknowledgedBy: a.acknowledged_by,
    acknowledgedAt: a.acknowledged_at ? a.acknowledged_at.toISOString() : null,
    note: a.ack_note,
  };
}

/** Alerts the caller can see, newest first (created_at desc, then threshold desc). */
async function queryAlerts(
  db: Db,
  user: AppUser,
  filter: { status: StatusFilter; groupId?: string | null; id?: number | null },
) {
  const r = await db.query<AlertRow>(
    `WITH ${VISIBLE_GROUPS}
     SELECT a.id, a.group_id, g.name AS group_name, a.member_id, m.first_name || ' ' || m.last_name AS member_name,
            a.policy_year_id, py.start_date, py.end_date, a.threshold, a.spend_cents_at_trigger,
            a.deductible_cents_at_trigger,
            COALESCE((SELECT sum(cc.effective_amount_cents) FROM claim_current cc
                      WHERE cc.member_id = a.member_id AND cc.policy_year_id = a.policy_year_id), 0)::bigint
              AS current_spend_cents,
            COALESCE(l.specific_deductible_cents, py.specific_deductible_cents) AS current_deductible_cents,
            a.status, a.created_at, u.filename AS upload_filename, a.acknowledged_by, a.acknowledged_at, a.ack_note
     FROM alert a
     JOIN visible_group g ON g.id = a.group_id
     JOIN member m ON m.id = a.member_id
     JOIN policy_year py ON py.id = a.policy_year_id
     LEFT JOIN laser l ON l.member_id = a.member_id AND l.policy_year_id = a.policy_year_id
     LEFT JOIN upload u ON u.id = a.upload_id
     WHERE ($3::text = 'all' OR a.status = $3)
       AND ($4::text IS NULL OR a.group_id = $4)
       AND ($5::bigint IS NULL OR a.id = $5)
     ORDER BY a.created_at DESC, a.threshold DESC, a.id DESC`,
    [...scopeParams(user), filter.status, filter.groupId ?? null, filter.id ?? null],
  );
  return r.rows.map(alertJson);
}

export function alertRoutes(db: Db): Router {
  const router = Router();

  router.get('/alerts', async (req, res) => {
    const user = currentUser(res);
    const status = (req.query.status ?? 'open') as StatusFilter;
    if (!STATUSES.includes(status)) throw new HttpError(400, 'status must be one of open, acknowledged, all');
    const groupId = req.query.groupId;
    if (groupId !== undefined && typeof groupId !== 'string') throw new HttpError(400, 'groupId must be a string');
    if (groupId) {
      // Same rule as the group endpoints: a group you cannot see does not exist.
      const g = await db.query(`WITH ${VISIBLE_GROUPS} SELECT 1 FROM visible_group WHERE id = $3`, [
        ...scopeParams(user),
        groupId,
      ]);
      if (g.rowCount === 0) throw new HttpError(404, 'Group not found');
    }
    res.json(await queryAlerts(db, user, { status, groupId: groupId || null }));
  });

  router.post('/alerts/:id/acknowledge', requireAnalyst, async (req, res) => {
    const user = currentUser(res);
    const id = parseId(req.params.id);
    if (id === null) throw new HttpError(404, 'Alert not found');
    const body = req.body as { note?: unknown } | undefined;
    const note = typeof body?.note === 'string' ? body.note.trim() : '';
    if (note === '') throw new HttpError(400, 'A note is required to acknowledge an alert');
    if (note.length > MAX_NOTE_LENGTH) throw new HttpError(400, `The note must be at most ${MAX_NOTE_LENGTH} characters`);

    // Atomic open → acknowledged; a concurrent second acknowledge updates nothing and gets 409.
    const upd = await db.query(
      `WITH ${VISIBLE_GROUPS}
       UPDATE alert a SET status = 'acknowledged', acknowledged_by = $4, acknowledged_at = now(), ack_note = $5
       WHERE a.id = $3 AND a.status = 'open' AND a.group_id IN (SELECT id FROM visible_group)`,
      [...scopeParams(user), id, user.email, note],
    );
    const [alert] = await queryAlerts(db, user, { status: 'all', id });
    if (!alert) throw new HttpError(404, 'Alert not found');
    if (upd.rowCount === 0) throw new HttpError(409, `Alert is already ${alert.status}`);
    res.json(alert);
  });

  return router;
}
