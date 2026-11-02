import { centsToDollars, pctUsed } from '../domain/money.js';
import type { DbClient } from '../db.js';

export interface UploadResult {
  id: number;
  filename: string;
  groupId: string | null;
  layout: string | null;
  status: 'processed' | 'rejected';
  rejectionReason: string | null;
  uploadedBy: string;
  uploadedAt: string;
  sha256: string;
  counts: { rows: number; applied: number; unchanged: number; olderIgnored: number; summary: number; errors: number };
  errors: { rowNumber: number; reason: string; raw: string }[];
  alertsRaised: { id: number; memberId: string; threshold: number; spend: number; deductible: number; pctUsed: number }[];
}

export type UploadSummary = Omit<UploadResult, 'errors'>;

interface UploadRow {
  id: number;
  group_id: string | null;
  filename: string;
  sha256: string;
  layout: string | null;
  status: 'processed' | 'rejected';
  rejection_reason: string | null;
  uploaded_by: string;
  uploaded_at: Date;
  row_count: number;
  applied_count: number;
  unchanged_count: number;
  older_ignored_count: number;
  summary_count: number;
  error_count: number;
  alerts: { id: number; member_id: string; threshold: number; spend: number; ded: number }[];
}

const SELECT_UPLOAD = `
  SELECT u.id, u.group_id, u.filename, u.sha256, u.layout, u.status, u.rejection_reason, u.uploaded_by, u.uploaded_at,
         u.row_count, u.applied_count, u.unchanged_count, u.older_ignored_count, u.summary_count, u.error_count,
         COALESCE((SELECT json_agg(json_build_object('id', a.id, 'member_id', a.member_id, 'threshold', a.threshold,
                                                    'spend', a.spend_cents_at_trigger,
                                                    'ded', a.deductible_cents_at_trigger)
                                   ORDER BY a.member_id, a.threshold)
                   FROM alert a WHERE a.upload_id = u.id), '[]'::json) AS alerts
  FROM upload u`;

function toSummary(r: UploadRow): UploadSummary {
  return {
    id: r.id,
    filename: r.filename,
    groupId: r.group_id,
    layout: r.layout,
    status: r.status,
    rejectionReason: r.rejection_reason,
    uploadedBy: r.uploaded_by,
    uploadedAt: r.uploaded_at.toISOString(),
    sha256: r.sha256,
    counts: {
      rows: r.row_count,
      applied: r.applied_count,
      unchanged: r.unchanged_count,
      olderIgnored: r.older_ignored_count,
      summary: r.summary_count,
      errors: r.error_count,
    },
    alertsRaised: r.alerts.map((a) => ({
      id: Number(a.id),
      memberId: a.member_id,
      threshold: a.threshold,
      spend: centsToDollars(a.spend),
      deductible: centsToDollars(a.ded),
      pctUsed: pctUsed(Number(a.spend), Number(a.ded)),
    })),
  };
}

export async function getUpload(db: DbClient, id: number): Promise<UploadResult | null> {
  const res = await db.query<UploadRow>(`${SELECT_UPLOAD} WHERE u.id = $1`, [id]);
  const row = res.rows[0];
  if (!row) return null;
  const errors = await db.query<{ row_number: number; reason: string; raw: string }>(
    'SELECT row_number, reason, raw FROM upload_row_error WHERE upload_id = $1 ORDER BY row_number, id',
    [id],
  );
  return {
    ...toSummary(row),
    errors: errors.rows.map((e) => ({ rowNumber: e.row_number, reason: e.reason, raw: e.raw })),
  };
}

/** Newest first, without row errors. */
export async function listUploads(db: DbClient, limit = 200): Promise<UploadSummary[]> {
  const res = await db.query<UploadRow>(`${SELECT_UPLOAD} ORDER BY u.uploaded_at DESC, u.id DESC LIMIT $1`, [limit]);
  return res.rows.map(toSummary);
}
