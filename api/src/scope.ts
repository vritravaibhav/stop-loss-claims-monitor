import type { AppUser } from './auth.js';

/**
 * Broker scoping is done in SQL. Every group-scoped query starts with this CTE and joins through it, with
 * `$1` = caller is an analyst and `$2` = caller's brokerage id. A broker sees only groups of their brokerage; groups
 * without a brokerage are analyst-only (NULL = $2 is never true).
 */
export const VISIBLE_GROUPS = `visible_group AS (
  SELECT g.id, g.name, g.brokerage_id FROM employer_group g WHERE $1::boolean OR g.brokerage_id = $2::text
)`;

export function scopeParams(user: AppUser): [boolean, string | null] {
  return [user.role === 'analyst', user.role === 'broker' ? (user.brokerage?.id ?? null) : null];
}
