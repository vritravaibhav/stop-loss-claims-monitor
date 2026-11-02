/**
 * `npm run load-sample`: upload every file in $DATA_DIR/claims (default ../data/claims) in file-name order through
 * the same ingest code as POST /api/uploads. Safe to re-run: a re-sent file changes nothing.
 */
import path from 'node:path';
import { createPool, requireDatabaseUrl } from '../src/db.js';
import { loadSampleClaims } from '../src/services/loadSample.js';

async function main() {
  const dir = path.join(path.resolve(process.env.DATA_DIR ?? '../data'), 'claims');
  const db = createPool(requireDatabaseUrl(), { max: 2 });
  try {
    const results = await loadSampleClaims(db, dir, 'load-sample', (r) => {
      const c = r.counts;
      console.log(
        r.status === 'processed'
          ? `${r.filename}: rows=${c.rows} applied=${c.applied} unchanged=${c.unchanged} olderIgnored=${c.olderIgnored} ` +
              `summary=${c.summary} errors=${c.errors} alerts=${r.alertsRaised.length}`
          : `${r.filename}: REJECTED ${r.rejectionReason}`,
      );
      for (const e of r.errors) console.log(`    line ${e.rowNumber}: ${e.reason}`);
    });
    console.log(`load-sample: ${results.length} files from ${dir}`);
  } finally {
    await db.end();
  }
}

main().catch((err) => {
  console.error('load-sample failed:', err);
  process.exit(1);
});
