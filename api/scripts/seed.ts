/**
 * `npm run seed`: idempotent upsert of brokerages, groups, policy years, members, lasers and app users from
 * $DATA_DIR (default ../data); then, when FIREBASE_AUTH_EMULATOR_HOST is set or SEED_FIREBASE_USERS=true,
 * creates/updates the seed logins in Firebase Auth with password $SEED_PASSWORD (default Password123!, emulator only).
 */
import path from 'node:path';
import { createPool, requireDatabaseUrl, withTransaction } from '../src/db.js';
import { firebaseAuth } from '../src/firebase.js';
import { retryWhileUnavailable, upsertFirebaseUsers } from '../src/services/firebaseUsers.js';
import { loadReferenceData, SEED_USERS, seedReferenceData } from '../src/services/referenceData.js';

const DEFAULT_PASSWORD = 'Password123!';

async function main() {
  const dataDir = path.resolve(process.env.DATA_DIR ?? '../data');
  const data = loadReferenceData(dataDir);

  const db = createPool(requireDatabaseUrl(), { max: 1 });
  try {
    const counts = await withTransaction(db, async (client) => {
      // Several API instances may start at once; seed one at a time.
      await client.query(`SELECT pg_advisory_xact_lock(hashtext('stoploss.seed'))`);
      return seedReferenceData(client, data);
    });
    console.log(`seed: database upserted from ${dataDir}:`, counts);
  } finally {
    await db.end();
  }

  const emulator = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  if (!emulator && process.env.SEED_FIREBASE_USERS !== 'true') {
    console.log('seed: Firebase users skipped (set FIREBASE_AUTH_EMULATOR_HOST or SEED_FIREBASE_USERS=true)');
    return;
  }
  const password = process.env.SEED_PASSWORD || (emulator ? DEFAULT_PASSWORD : undefined);
  if (!password) throw new Error('SEED_PASSWORD must be set to seed users in a real Firebase project');
  const auth = firebaseAuth();
  await retryWhileUnavailable(() =>
    upsertFirebaseUsers(
      auth,
      SEED_USERS.map((u) => u.email),
      password,
      (m) => console.log(`seed: ${m}`),
    ),
  );
}

main().catch((err) => {
  console.error('seed failed:', err);
  process.exit(1);
});
