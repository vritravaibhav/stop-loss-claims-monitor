import { describe, expect, it } from 'vitest';
import { centsToDollars, excessCents, formatCents, parseAmountToCents, pctUsed } from '../../src/domain/money.js';

const cents = (s: string) => {
  const r = parseAmountToCents(s);
  if (!r.ok) throw new Error(r.reason);
  return r.cents;
};

describe('parseAmountToCents', () => {
  it.each([
    ['790.68', 79068],
    ['14,455.00', 1445500], // tpa_one quoted thousands (quotes already removed by the CSV reader)
    ['$615.09', 61509], // tpa_two
    ['$4,532.16', 453216],
    ['$1343.78', 134378], // tpa_two without a thousands separator
    ['$30000.00', 3000000],
    ['$0.00', 0],
    ['  $ 1,234.5 ', 123450],
    ['100', 10000],
    ['0.1', 10],
    ['0.07', 7],
    ['1,000,000.01', 100000001],
    ['999,999,999.99', 99999999999],
  ])('%s → %d cents', (input, expected) => {
    expect(cents(input)).toBe(expected);
  });

  it('never goes through floating point (0.1 + 0.2 style values stay exact)', () => {
    expect(cents('0.29')).toBe(29);
    expect(cents('1.15')).toBe(115);
    expect(cents('4.35')).toBe(435);
    expect(cents('1,005.03')).toBe(100503);
  });

  it.each([
    ['N/A', /invalid paid amount "N\/A"/],
    ['', /blank/],
    ['   ', /blank/],
    ['abc', /invalid/],
    ['12.345', /invalid/], // more than 2 dp
    ['1234,56', /invalid/], // decimal comma must not become 123456
    ['1,23.00', /invalid/], // malformed grouping
    ['12,34,567.00', /invalid/],
    ['1.2.3', /invalid/],
    ['$', /invalid/],
    ['.', /invalid/],
    ['-12.00', /negative/],
    ['$-12.00', /negative/],
    ['-$12.00', /negative/],
    ['(12.00)', /negative/],
    ['1e5', /invalid/],
    ['1,000,000,000.00', /out of range/],
  ])('rejects %j', (input, reason) => {
    const r = parseAmountToCents(input);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.reason).toMatch(reason);
  });
});

describe('money formatting', () => {
  it('converts cents to dollars at the edge', () => {
    expect(centsToDollars(1800000)).toBe(18000);
    expect(centsToDollars('47899')).toBe(478.99);
    expect(centsToDollars(1n)).toBe(0.01);
  });
  it('formats cents for messages with integer maths', () => {
    expect(formatCents(47899)).toBe('478.99');
    expect(formatCents(1445500)).toBe('14,455.00');
    expect(formatCents(5)).toBe('0.05');
  });
  it('truncates % used to 1 dp so 49.99% never displays as 50.0', () => {
    expect(pctUsed(2_499_950, 5_000_000)).toBe(49.9);
    expect(pctUsed(2_500_000, 5_000_000)).toBe(50);
    expect(pctUsed(5_600_000, 5_000_000)).toBe(112);
    expect(pctUsed(1_845_323, 7_500_000)).toBe(24.6);
    expect(pctUsed(0, 5_000_000)).toBe(0);
  });
  it('excess is never negative', () => {
    expect(excessCents(5_600_000, 5_000_000)).toBe(600_000);
    expect(excessCents(100, 5_000_000)).toBe(0);
  });
});
