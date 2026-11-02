import cors from 'cors';
import express, { type Express } from 'express';
import { authenticate, type VerifyToken } from './auth.js';
import type { Db } from './db.js';
import { errorHandler, HttpError } from './http.js';
import { alertRoutes } from './routes/alerts.js';
import { groupRoutes } from './routes/groups.js';
import { uploadRoutes } from './routes/uploads.js';

export interface AppOptions {
  db: Db;
  /** Bearer-token verifier. Production passes `firebaseTokenVerifier(...)`; tests pass a fake. */
  verifyToken: VerifyToken;
  /** Browser origins allowed by CORS (dev only; in prod the web app and API share an origin). */
  corsOrigins?: string[];
  /** Today's date as YYYY-MM-DD (UTC by default), for "current policy year". */
  today?: () => string;
}

export function parseCorsOrigins(value: string | undefined): string[] {
  return (value ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function createApp(opts: AppOptions): Express {
  const today = opts.today ?? (() => new Date().toISOString().slice(0, 10));
  const app = express();
  app.disable('x-powered-by');
  app.use((_req, res, next) => {
    res.set('X-Content-Type-Options', 'nosniff');
    next();
  });
  if (opts.corsOrigins?.length) {
    app.use(
      cors({
        origin: opts.corsOrigins,
        methods: ['GET', 'POST'],
        allowedHeaders: ['Authorization', 'Content-Type'],
        maxAge: 600,
      }),
    );
  }

  app.get('/healthz', (_req, res) => {
    res.json({ ok: true });
  });

  const api = express.Router();
  api.use((_req, res, next) => {
    res.set('Cache-Control', 'no-store'); // member health spend: never cached by browsers or proxies
    next();
  });
  // Authenticate before reading any body.
  api.use(authenticate(opts.db, opts.verifyToken));
  api.use(express.json({ limit: '16kb' }));
  api.use(groupRoutes(opts.db, today));
  api.use(alertRoutes(opts.db));
  api.use(uploadRoutes(opts.db));
  api.use(() => {
    throw new HttpError(404, 'Not found');
  });
  app.use('/api', api);

  app.use((_req, res) => {
    res.status(404).json({ error: 'Not found' });
  });
  app.use(errorHandler);
  return app;
}
