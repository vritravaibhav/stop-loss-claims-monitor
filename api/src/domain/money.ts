/**
 * Money is integer cents everywhere inside the API and DB. These helpers convert at the edges only.
 * No floating point is ever used to *compute* with money; `centsToDollars` produces the JSON number for responses.
 */

/** Largest amount accepted from a file: $999,999,999.99. Keeps every sum far inside Number.MAX_SAFE_INTEGER. */
export const MAX_AMOUNT_CENTS = 99_999_999_999;

export type AmountResult = { ok: true; cents: number } | { ok: false; reason: string };

const PLAIN = /^(\d+)(?:\.(\d{1,2}))?$/;
const GROUPED = /^(\d{1,3}(?:,\d{3})+)(?:\.(\d{1,2}))?$/;

/**
 * Parse a paid amount such as `14,455.00`, `$4,532.16`, `$1343.78`, `615.09`, `"$30,000.00"` into integer cents.
 *
 * - `$` and surrounding whitespace are allowed; at most 2 decimal places.
 * - Thousands separators must be well-formed (`1,234.56`); `1234,56` (a decimal comma) is rejected rather than
 *   silently read as 123456.
 * - Negative amounts (`-12.00`, `(12.00)`) are rejected: a reversal is signalled by the status column.
 * - Blank, `N/A` and anything else that is not a number are rejected.
 */
export function parseAmountToCents(input: string): AmountResult {
  const original = input.trim();
  if (original === '') return { ok: false, reason: 'paid amount is blank' };

  let s = original;
  let negative = false;
  if (s.startsWith('(') && s.endsWith(')')) {
    negative = true;
    s = s.slice(1, -1).trim();
  }
  if (s.startsWith('-')) {
    negative = true;
    s = s.slice(1).trim();
  }
  if (s.startsWith('$')) s = s.slice(1).trim();
  if (s.startsWith('-')) {
    negative = true;
    s = s.slice(1).trim();
  }
  s = s.replace(/\s+/g, '');

  const m = PLAIN.exec(s) ?? GROUPED.exec(s);
  if (!m) return { ok: false, reason: `invalid paid amount "${original}"` };
  if (negative) return { ok: false, reason: `negative paid amount "${original}" (reversals must use the status column)` };

  const whole = m[1]!.replace(/,/g, '');
  const frac = (m[2] ?? '').padEnd(2, '0');
  if (whole.replace(/^0+/, '').length > 9) return { ok: false, reason: `paid amount "${original}" is out of range` };
  const cents = Number(whole) * 100 + Number(frac);
  if (!Number.isSafeInteger(cents) || cents > MAX_AMOUNT_CENTS) {
    return { ok: false, reason: `paid amount "${original}" is out of range` };
  }
  return { ok: true, cents };
}

/** Integer cents (number, bigint or the string pg returns for int8/numeric) → safe integer number of cents. */
export function toCents(value: number | bigint | string | null | undefined): number {
  if (value === null || value === undefined) return 0;
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isSafeInteger(n)) throw new Error(`not an integer number of cents: ${String(value)}`);
  return n;
}

/** Integer cents → dollars as a JSON number (e.g. 1800000 → 18000, 47899 → 478.99). */
export function centsToDollars(cents: number | bigint | string): number {
  return toCents(cents) / 100;
}

/** `cents` as a human string for messages, e.g. 47899 → "478.99". Integer maths only. */
export function formatCents(cents: number): string {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100);
  const frac = String(abs % 100).padStart(2, '0');
  return `${sign}${whole.toLocaleString('en-US')}.${frac}`;
}

/**
 * Percentage of the deductible used, to 1 decimal place, **truncated** (not rounded) so that a member at 49.99%
 * shows 49.9 and never "50.0" without a 50% alert. Integer maths via BigInt.
 */
export function pctUsed(spendCents: number, deductibleCents: number): number {
  if (deductibleCents <= 0) throw new Error('deductible must be positive');
  const tenths = (BigInt(spendCents) * 1000n) / BigInt(deductibleCents);
  return Number(tenths) / 10;
}

export function excessCents(spendCents: number, deductibleCents: number): number {
  return Math.max(0, spendCents - deductibleCents);
}
