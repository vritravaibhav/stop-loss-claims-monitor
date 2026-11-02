import { decodeText, parseCsvLine, splitLines } from './csv.js';
import { parseDate } from './dates.js';
import { detectLayout, type Field, type Layout } from './layouts.js';
import { parseAmountToCents } from './money.js';

export type ClaimStatus = 'paid' | 'reversed';

/** One claim row after field parsing and normalisation. Dates are YYYY-MM-DD, money is integer cents. */
export interface ClaimRow {
  claimId: string;
  version: number;
  memberId: string;
  serviceDate: string;
  paidDate: string | null;
  amountCents: number;
  status: ClaimStatus;
  diagnosisCode: string | null;
}

export type ParsedLine =
  | { kind: 'claim'; rowNumber: number; raw: string; claim: ClaimRow }
  | { kind: 'error'; rowNumber: number; raw: string; reason: string }
  | { kind: 'summary'; rowNumber: number; raw: string };

export type ParsedFile =
  | { ok: true; layout: Layout; headerRowNumber: number; lines: ParsedLine[] }
  | { ok: false; layout: Layout | null; reason: string };

const PAID = new Set(['PAID', 'P']);
const REVERSED = new Set(['REVERSED', 'R', 'VOID', 'VOIDED', 'V']);
const SUMMARY_MARKERS = new Set(['TRAILER', 'TOTAL', 'TOTALS', 'SUMMARY', 'GRAND TOTAL']);
const MAX_VERSION = 2_147_483_647;
const MAX_ID_LENGTH = 64;

export function parseStatus(input: string): { ok: true; status: ClaimStatus } | { ok: false; reason: string } {
  const s = input.trim().toUpperCase();
  if (PAID.has(s)) return { ok: true, status: 'paid' };
  if (REVERSED.has(s)) return { ok: true, status: 'reversed' };
  if (s === '') return { ok: false, reason: 'status is blank' };
  return { ok: false, reason: `unknown status "${input.trim()}" (expected PAID/P or REVERSED/R/VOID/V)` };
}

export function parseVersion(input: string): { ok: true; version: number } | { ok: false; reason: string } {
  const s = input.trim();
  if (s === '') return { ok: false, reason: 'version is blank' };
  if (!/^\d+$/.test(s)) return { ok: false, reason: `invalid version "${s}" (expected a whole number ≥ 0)` };
  const v = Number(s);
  if (!Number.isSafeInteger(v) || v > MAX_VERSION) return { ok: false, reason: `version "${s}" is out of range` };
  return { ok: true, version: v };
}

/** Is this row a summary/total row (e.g. the `TRAILER` line of TPA One files)? */
export function isSummaryRow(cells: string[], claimIdIndex: number | undefined): boolean {
  const first = (cells[0] ?? '').trim().replace(/:$/, '').toUpperCase();
  if (SUMMARY_MARKERS.has(first)) return true;
  if (claimIdIndex !== undefined) {
    const c = (cells[claimIdIndex] ?? '').trim().replace(/:$/, '').toUpperCase();
    if (SUMMARY_MARKERS.has(c)) return true;
  }
  return false;
}

function isBlankRow(line: string): boolean {
  // An empty line, or one made only of separators/whitespace/empty quotes (",,,,,,,").
  return /^[\s,"]*$/.test(line);
}

/** Parse the cells of one data row (already known not to be blank/summary) into a ClaimRow or a list of reasons. */
export function parseClaimCells(
  cells: string[],
  indexes: Partial<Record<Field, number>>,
  columnCount: number,
): { ok: true; claim: ClaimRow } | { ok: false; reason: string } {
  // Trailing empty cells beyond the header are tolerated (trailing commas); anything else means misaligned columns.
  let used = cells.length;
  while (used > columnCount && (cells[used - 1] ?? '').trim() === '') used--;
  if (used !== columnCount) {
    return { ok: false, reason: `Expected ${columnCount} columns, got ${used}` };
  }
  const get = (f: Field): string => {
    const idx = indexes[f];
    return idx === undefined ? '' : (cells[idx] ?? '');
  };

  const reasons: string[] = [];

  const claimId = get('claimId').trim();
  if (claimId === '') reasons.push('claim ID is blank');
  else if (claimId.length > MAX_ID_LENGTH) reasons.push('claim ID is too long');

  const version = parseVersion(get('version'));
  if (!version.ok) reasons.push(version.reason);

  const memberId = get('memberId').trim();
  if (memberId === '') reasons.push('member ID is blank');
  else if (memberId.length > MAX_ID_LENGTH) reasons.push('member ID is too long');

  const serviceDate = parseDate(get('serviceDate'), 'service date');
  if (!serviceDate.ok) reasons.push(serviceDate.reason);

  const paidRaw = get('paidDate').trim();
  const paidDate = paidRaw === '' ? null : parseDate(paidRaw, 'paid date');
  if (paidDate && !paidDate.ok) reasons.push(paidDate.reason);

  const amount = parseAmountToCents(get('amount'));
  if (!amount.ok) reasons.push(amount.reason);

  const status = parseStatus(get('status'));
  if (!status.ok) reasons.push(status.reason);

  const diagnosis = get('diagnosisCode').trim();

  if (reasons.length > 0 || !version.ok || !serviceDate.ok || !amount.ok || !status.ok || (paidDate && !paidDate.ok)) {
    return { ok: false, reason: capitalise(reasons.join('; ')) };
  }
  return {
    ok: true,
    claim: {
      claimId,
      version: version.version,
      memberId,
      serviceDate: serviceDate.date,
      paidDate: paidDate ? paidDate.date : null,
      amountCents: amount.cents,
      status: status.status,
      diagnosisCode: diagnosis === '' ? null : diagnosis,
    },
  };
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * Parse a whole claim file (pure; no DB). Rows are numbered by physical line (1-based, header and comment lines
 * counted). Lines before the header that are blank or start with `#` are skipped; blank rows are ignored.
 */
export function parseClaimFile(content: Buffer | string): ParsedFile {
  const lines = splitLines(decodeText(content));

  let headerIndex = -1;
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i]!.trim();
    if (t === '' || t.startsWith('#') || isBlankRow(t)) continue;
    headerIndex = i;
    break;
  }
  if (headerIndex === -1) return { ok: false, layout: null, reason: 'file has no header row' };

  const header = parseCsvLine(lines[headerIndex]!);
  if (!header.ok) return { ok: false, layout: null, reason: `header row: ${header.reason}` };
  const match = detectLayout(header.cells);
  if (!match.ok) return { ok: false, layout: null, reason: match.reason };

  const out: ParsedLine[] = [];
  for (let i = headerIndex + 1; i < lines.length; i++) {
    const raw = lines[i]!;
    const rowNumber = i + 1;
    if (isBlankRow(raw)) continue;
    const parsed = parseCsvLine(raw);
    if (!parsed.ok) {
      out.push({ kind: 'error', rowNumber, raw, reason: capitalise(parsed.reason) });
      continue;
    }
    if (isSummaryRow(parsed.cells, match.indexes.claimId)) {
      out.push({ kind: 'summary', rowNumber, raw });
      continue;
    }
    const row = parseClaimCells(parsed.cells, match.indexes, match.columnCount);
    if (row.ok) out.push({ kind: 'claim', rowNumber, raw, claim: row.claim });
    else out.push({ kind: 'error', rowNumber, raw, reason: row.reason });
  }
  return { ok: true, layout: match.layout, headerRowNumber: headerIndex + 1, lines: out };
}
