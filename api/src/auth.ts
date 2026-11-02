import type { Auth } from 'firebase-admin/auth';
import type { RequestHandler, Response } from 'express';
import type { Db } from './db.js';
import { HttpError } from './http.js';

/** What the API needs from a verified ID token. */
export interface VerifiedIdentity {
  email: string | null;
  emailVerified: boolean;
}

/**
 * Verifies a bearer token or throws. Injected into `createApp` so tests can pass a fake; production code only ever
 * builds this from firebase-admin (`firebaseTokenVerifier`).
 */
export type VerifyToken = (idToken: string) => Promise<VerifiedIdentity>;

export function firebaseTokenVerifier(auth: Auth): VerifyToken {
  return async (idToken) => {
    const decoded = await auth.verifyIdToken(idToken);
    return { email: decoded.email ?? null, emailVerified: decoded.email_verified === true };
  };
}

export interface AppUser {
  id: number;
  email: string;
  role: 'analyst' | 'broker';
  brokerage: { id: string; name: string } | null;
}

/** The caller, as loaded from `app_user` by `authenticate`. */
export function currentUser(res: Response): AppUser {
  const user = res.locals.user as AppUser | undefined;
  if (!user) throw new Error('currentUser() used on a route without authenticate()');
  return user;
}

const BEARER = /^Bearer[ \t]+(\S+)[ \t]*$/i;

/**
 * 401 unless the request carries a valid ID token; 403 unless the token's (verified) email is a row in `app_user`.
 * Role and brokerage come from that row, never from token claims.
 */
export function authenticate(db: Db, verifyToken: VerifyToken): RequestHandler {
  return async (req, res, next) => {
    const match = BEARER.exec(req.get('authorization') ?? '');
    if (!match) {
      res.set('WWW-Authenticate', 'Bearer');
      throw new HttpError(401, 'Missing bearer token');
    }
    let identity: VerifiedIdentity;
    try {
      identity = await verifyToken(match[1]!);
    } catch {
      res.set('WWW-Authenticate', 'Bearer error="invalid_token"');
      throw new HttpError(401, 'Invalid or expired token');
    }
    if (!identity.email) throw new HttpError(403, 'This sign-in has no email address');
    // Accounts are matched by email, so the email must be proven to belong to the person signing in.
    if (!identity.emailVerified) throw new HttpError(403, 'Email address is not verified');

    const r = await db.query<{
      id: number;
      email: string;
      role: 'analyst' | 'broker';
      brokerage_id: string | null;
      brokerage_name: string | null;
    }>(
      `SELECT u.id, u.email, u.role, b.id AS brokerage_id, b.name AS brokerage_name
       FROM app_user u LEFT JOIN brokerage b ON b.id = u.brokerage_id
       WHERE u.email = lower($1)`,
      [identity.email.trim()],
    );
    const row = r.rows[0];
    if (!row) throw new HttpError(403, 'This account has no access to the stop-loss monitor');
    const user: AppUser = {
      id: row.id,
      email: row.email,
      role: row.role,
      brokerage: row.brokerage_id ? { id: row.brokerage_id, name: row.brokerage_name ?? row.brokerage_id } : null,
    };
    res.locals.user = user;
    next();
  };
}

export const requireAnalyst: RequestHandler = (_req, res, next) => {
  if (currentUser(res).role !== 'analyst') throw new HttpError(403, 'Only analysts can do this');
  next();
};
