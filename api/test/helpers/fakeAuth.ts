import type { VerifyToken } from '../../src/auth.js';

/**
 * Test-only token verifier: `test:verified:<email>` / `test:unverified:<email>` / `test:verified:` (no email).
 * Anything else is rejected like a bad Firebase token. Lives under test/, so production code cannot import it.
 */
export const fakeVerifyToken: VerifyToken = async (token) => {
  const m = /^test:(verified|unverified):(.*)$/.exec(token);
  if (!m) throw new Error('invalid token');
  return { email: m[2] || null, emailVerified: m[1] === 'verified' };
};

export const ANALYST = 'analyst@example.test';
export const BROKER_A = 'broker.a@example.test';
export const BROKER_B = 'broker.b@example.test';

export const bearer = (email: string, verified = true) =>
  `Bearer test:${verified ? 'verified' : 'unverified'}:${email}`;
