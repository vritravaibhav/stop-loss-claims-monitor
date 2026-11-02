/**
 * Calendar dates are handled as `YYYY-MM-DD` strings (no time zone, no `Date` objects), which is also how the
 * API returns them and how pg is configured to hand `date` columns back.
 */

export type DateResult = { ok: true; date: string } | { ok: false; reason: string };

const US = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/; // MM/DD/YYYY (tpa_one); single-digit month/day tolerated
const ISO = /^(\d{4})-(\d{2})-(\d{2})$/; // YYYY-MM-DD (tpa_two)

export function isLeapYear(y: number): boolean {
  return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
}

export function daysInMonth(y: number, m: number): number {
  return [31, isLeapYear(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1] ?? 0;
}

/** Build an ISO date string if (y, m, d) is a real calendar date in a sane range, else null. */
export function isoDate(y: number, m: number, d: number): string | null {
  if (y < 1900 || y > 2200) return null;
  if (m < 1 || m > 12) return null;
  if (d < 1 || d > daysInMonth(y, m)) return null;
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/**
 * Parse `MM/DD/YYYY` or `YYYY-MM-DD` into `YYYY-MM-DD`; the result must be a real calendar date
 * (`02/30/2026`, `2026-13-01` are rejected). `label` names the field in the error message.
 */
export function parseDate(input: string, label: string): DateResult {
  const s = input.trim();
  if (s === '') return { ok: false, reason: `${label} is blank` };
  let y: number, m: number, d: number;
  let match = US.exec(s);
  if (match) {
    m = Number(match[1]);
    d = Number(match[2]);
    y = Number(match[3]);
  } else if ((match = ISO.exec(s))) {
    y = Number(match[1]);
    m = Number(match[2]);
    d = Number(match[3]);
  } else {
    return { ok: false, reason: `invalid ${label} "${s}" (expected MM/DD/YYYY or YYYY-MM-DD)` };
  }
  const iso = isoDate(y, m, d);
  if (!iso) return { ok: false, reason: `invalid ${label} "${s}" (not a real calendar date)` };
  return { ok: true, date: iso };
}

/** Today's date (UTC) as YYYY-MM-DD. */
export function todayIso(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}
