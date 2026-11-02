import type { Auth } from 'firebase-admin/auth';

/** Create each user, or reset its password; always email-verified (the API rejects unverified emails). */
export async function upsertFirebaseUsers(
  auth: Auth,
  emails: readonly string[],
  password: string,
  log: (msg: string) => void = console.log,
): Promise<void> {
  for (const email of emails) {
    let uid: string | null = null;
    try {
      uid = (await auth.getUserByEmail(email)).uid;
    } catch (err) {
      if ((err as { code?: string }).code !== 'auth/user-not-found') throw err;
    }
    if (uid) {
      await auth.updateUser(uid, { password, emailVerified: true, disabled: false });
      log(`firebase user updated: ${email}`);
    } else {
      await auth.createUser({ email, password, emailVerified: true });
      log(`firebase user created: ${email}`);
    }
  }
}

/** Retry `fn` while the error looks like "service not up yet" (the emulator may still be starting). */
export async function retryWhileUnavailable<T>(fn: () => Promise<T>, attempts = 30, delayMs = 1000): Promise<T> {
  for (let i = 1; ; i++) {
    try {
      return await fn();
    } catch (err) {
      const text = String((err as { message?: string }).message ?? err) + String((err as { cause?: unknown }).cause ?? '');
      const transient = /ECONNREFUSED|ECONNRESET|ENOTFOUND|EAI_AGAIN|socket hang up|fetch failed/i.test(text);
      if (!transient || i >= attempts) throw err;
      await new Promise((r) => setTimeout(r, delayMs));
    }
  }
}
