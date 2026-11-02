/**
 * Claim-file layouts. A layout is recognised from the header row (not from the group), columns are mapped by
 * name (any order, unknown extra columns ignored), and header matching ignores case, whitespace and punctuation
 * (`Claim ID`, `claim id`, `CLAIM_ID` all match).
 */

export type Field = 'claimId' | 'version' | 'memberId' | 'serviceDate' | 'paidDate' | 'amount' | 'status' | 'diagnosisCode';

export interface Layout {
  id: string;
  description: string;
  /** Header name (as the TPA spells it) for each field. */
  columns: Partial<Record<Field, string>>;
  /** Fields whose *column* may be missing from the header. (Values of paid date / diagnosis may always be blank.) */
  optionalColumns: Field[];
}

export const LAYOUTS: readonly Layout[] = [
  {
    id: 'tpa_one',
    description: 'TPA One (Group A): MM/DD/YYYY dates, PAID/REVERSED, Adj Seq from 0',
    columns: {
      claimId: 'Claim ID',
      version: 'Adj Seq',
      memberId: 'Member ID',
      serviceDate: 'Service Date',
      paidDate: 'Paid Date',
      amount: 'Paid Amount',
      status: 'Status',
      diagnosisCode: 'Diagnosis Code',
    },
    optionalColumns: ['paidDate', 'diagnosisCode'],
  },
  {
    id: 'tpa_two',
    description: 'TPA Two (Group B): ISO dates, $ amounts, P/V status, version from 1',
    columns: {
      claimId: 'claim_number',
      version: 'version',
      memberId: 'member',
      serviceDate: 'dos',
      paidDate: 'paid_dt',
      amount: 'amount',
      status: 'claim_status',
    },
    optionalColumns: ['paidDate'],
  },
];

/** `Claim ID` → `claimid`, `claim_number` → `claimnumber`. */
export function normaliseHeader(name: string): string {
  return name.replace(/^﻿/, '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

export type LayoutMatch =
  | { ok: true; layout: Layout; indexes: Partial<Record<Field, number>>; columnCount: number }
  | { ok: false; reason: string };

/** Match a header row (raw cells) against the known layouts. */
export function detectLayout(headerCells: string[], layouts: readonly Layout[] = LAYOUTS): LayoutMatch {
  const normalised = headerCells.map(normaliseHeader);
  // Trailing empty header cells (e.g. a trailing comma) are not columns.
  let columnCount = normalised.length;
  while (columnCount > 0 && normalised[columnCount - 1] === '') columnCount--;

  const seen = new Map<string, number>();
  for (let i = 0; i < columnCount; i++) {
    const h = normalised[i]!;
    if (h === '') continue;
    if (seen.has(h)) return { ok: false, reason: `duplicate column "${headerCells[i]!.trim()}" in header` };
    seen.set(h, i);
  }

  let best: { layout: Layout; missing: string[]; matched: number } | null = null;
  for (const layout of layouts) {
    const indexes: Partial<Record<Field, number>> = {};
    const missing: string[] = [];
    let matched = 0;
    for (const [field, name] of Object.entries(layout.columns) as [Field, string][]) {
      const idx = seen.get(normaliseHeader(name));
      if (idx === undefined) {
        if (!layout.optionalColumns.includes(field)) missing.push(name);
      } else {
        indexes[field] = idx;
        matched++;
      }
    }
    if (missing.length === 0) return { ok: true, layout, indexes, columnCount };
    if (!best || matched > best.matched) best = { layout, missing, matched };
  }

  if (best && best.matched >= 2) {
    return {
      ok: false,
      reason: `header looks like layout ${best.layout.id} but is missing column(s): ${best.missing.join(', ')}`,
    };
  }
  const expected = layouts.map((l) => `${l.id} (${Object.values(l.columns).join(', ')})`).join(' or ');
  return { ok: false, reason: `unrecognised header; expected the columns of ${expected}` };
}
