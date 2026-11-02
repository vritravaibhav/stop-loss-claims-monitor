/**
 * Alert thresholds (STOP_LOSS_RULES.md "Alerts"). "Reaches" means `>=`. Integer maths only:
 * spend reaches t% of the deductible  ⇔  spend_cents × 100 ≥ t × deductible_cents.
 */
export const THRESHOLDS = [50, 75, 100] as const;
export type Threshold = (typeof THRESHOLDS)[number];

export function reachesThreshold(spendCents: number, deductibleCents: number, threshold: number): boolean {
  if (deductibleCents <= 0) throw new Error('deductible must be positive');
  return BigInt(spendCents) * 100n >= BigInt(threshold) * BigInt(deductibleCents);
}

/** Every threshold the spend has reached (one file can cross several; each becomes its own alert). */
export function reachedThresholds(spendCents: number, deductibleCents: number): Threshold[] {
  return THRESHOLDS.filter((t) => reachesThreshold(spendCents, deductibleCents, t));
}

/**
 * Thresholds that should raise a *new* alert: reached now and never alerted before for this member and policy
 * year. (The DB's unique constraint is the real guard; this keeps the rule testable without a DB.)
 */
export function newAlertThresholds(
  spendCents: number,
  deductibleCents: number,
  alreadyAlerted: Iterable<number>,
): Threshold[] {
  const done = new Set(alreadyAlerted);
  return reachedThresholds(spendCents, deductibleCents).filter((t) => !done.has(t));
}
