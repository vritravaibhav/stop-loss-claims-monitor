import type { ClaimRow } from './claimFile.js';
import { formatCents } from './money.js';

/**
 * Claim-version rules (STOP_LOSS_RULES.md "Claim versions"):
 *  3. the newest (highest) version replaces all earlier ones;
 *  5. a version older than one already loaded changes nothing;
 *  6. the same version again with the same values changes nothing; with different values it is an error.
 */
export type VersionDecision =
  | { outcome: 'new' } // highest version so far → becomes current
  | { outcome: 'older_ignored' } // stored for history, current unchanged
  | { outcome: 'unchanged' } // exact duplicate of a stored version
  | { outcome: 'conflict'; differences: string[] }; // same version, different values → row error

/** The values that make a version "the same" (rule 6). */
export type VersionValues = Pick<
  ClaimRow,
  'memberId' | 'serviceDate' | 'paidDate' | 'amountCents' | 'status' | 'diagnosisCode'
>;

export interface StoredVersion extends VersionValues {
  version: number;
  /** Where it was first loaded, for the conflict message. */
  filename?: string;
  rowNumber?: number;
}

export interface ClaimHistory {
  /** Highest stored version number. */
  currentVersion: number;
  versions: Map<number, StoredVersion>;
}

export function compareValues(stored: VersionValues, incoming: VersionValues): string[] {
  const diffs: string[] = [];
  const show = (v: string | null) => (v === null ? '(blank)' : v);
  if (stored.memberId !== incoming.memberId) diffs.push(`member ${stored.memberId} → ${incoming.memberId}`);
  if (stored.serviceDate !== incoming.serviceDate) {
    diffs.push(`service date ${stored.serviceDate} → ${incoming.serviceDate}`);
  }
  if (stored.paidDate !== incoming.paidDate) diffs.push(`paid date ${show(stored.paidDate)} → ${show(incoming.paidDate)}`);
  if (stored.amountCents !== incoming.amountCents) {
    diffs.push(`paid amount ${formatCents(stored.amountCents)} → ${formatCents(incoming.amountCents)}`);
  }
  if (stored.status !== incoming.status) diffs.push(`status ${stored.status} → ${incoming.status}`);
  if ((stored.diagnosisCode ?? null) !== (incoming.diagnosisCode ?? null)) {
    diffs.push(`diagnosis code ${show(stored.diagnosisCode)} → ${show(incoming.diagnosisCode)}`);
  }
  return diffs;
}

/** Decide what an incoming row does to a claim, given everything already stored for that claim. */
export function decideVersion(
  history: ClaimHistory | undefined,
  incoming: VersionValues & { version: number },
): VersionDecision {
  if (!history) return { outcome: 'new' };
  const stored = history.versions.get(incoming.version);
  if (stored) {
    const differences = compareValues(stored, incoming);
    return differences.length === 0 ? { outcome: 'unchanged' } : { outcome: 'conflict', differences };
  }
  if (incoming.version > history.currentVersion) return { outcome: 'new' };
  return { outcome: 'older_ignored' };
}

/** Record a newly stored version into the in-memory history (used while processing a file in order). */
export function recordVersion(
  histories: Map<string, ClaimHistory>,
  claimId: string,
  version: StoredVersion,
): void {
  const h = histories.get(claimId);
  if (!h) {
    histories.set(claimId, { currentVersion: version.version, versions: new Map([[version.version, version]]) });
    return;
  }
  h.versions.set(version.version, version);
  if (version.version > h.currentVersion) h.currentVersion = version.version;
}

export function conflictReason(claimId: string, version: number, stored: StoredVersion, differences: string[]): string {
  const where =
    stored.filename !== undefined && stored.rowNumber !== undefined
      ? ` (first loaded from ${stored.filename} row ${stored.rowNumber})`
      : '';
  return (
    `Conflict: claim ${claimId} version ${version} was already loaded with different values${where}: ` +
    `${differences.join(', ')}. The first-loaded values were kept`
  );
}
