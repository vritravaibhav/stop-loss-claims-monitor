import { createApp, parseCorsOrigins } from './app.js';
import { firebaseTokenVerifier } from './auth.js';
import { createPool, requireDatabaseUrl } from './db.js';
import { firebaseAuth } from './firebase.js';

const db = createPool(requireDatabaseUrl());
db.on('error', (err) => console.error('postgres pool error', err));

const app = createApp({
  db,
  verifyToken: firebaseTokenVerifier(firebaseAuth()),
  corsOrigins: parseCorsOrigins(process.env.CORS_ORIGINS),
});

const port = Number(process.env.PORT ?? 8080);
const server = app.listen(port, (err?: Error) => {
  if (err) {
    console.error(`cannot listen on port ${port}`, err);
    process.exit(1);
  }
  const emulator = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  console.log(`stoploss api listening on :${port}${emulator ? ` (Firebase Auth emulator at ${emulator})` : ''}`);
});

function shutdown(signal: string) {
  console.log(`${signal}: shutting down`);
  setTimeout(() => process.exit(1), 10_000).unref();
  server.close(() => {
    db.end().finally(() => process.exit(0));
  });
}
process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));
