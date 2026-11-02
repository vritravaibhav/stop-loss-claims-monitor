import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import type { Db } from '../db.js';
import { ingestClaimFile } from './ingest.js';
import type { UploadResult } from './uploads.js';

/**
 * File-name order = plain code-unit order. Locale-aware comparison (`localeCompare`, `Intl.Collator`) would put
 * `GA_2026-04_resend.csv` *before* `GA_2026-04.csv` because it ignores punctuation at the first level.
 */
export function compareFileNames(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function listClaimFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.csv'))
    .map((e) => e.name)
    .sort(compareFileNames);
}

/** Load every claim file in `dir`, in file-name order, through the same ingest code as the HTTP upload. */
export async function loadSampleClaims(
  db: Db,
  dir: string,
  uploadedBy = 'seed-script',
  onFile: (r: UploadResult) => void = () => undefined,
): Promise<UploadResult[]> {
  const out: UploadResult[] = [];
  for (const name of listClaimFiles(dir)) {
    const r = await ingestClaimFile(db, { filename: name, content: readFileSync(path.join(dir, name)), uploadedBy });
    onFile(r);
    out.push(r);
  }
  return out;
}
