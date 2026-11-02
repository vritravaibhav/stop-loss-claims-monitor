import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';

export function firebaseProjectId(env: NodeJS.ProcessEnv = process.env): string | undefined {
  return env.FIREBASE_PROJECT_ID || env.GCLOUD_PROJECT || env.GOOGLE_CLOUD_PROJECT || undefined;
}

/**
 * With FIREBASE_AUTH_EMULATOR_HOST set, firebase-admin accepts *unsigned* tokens. Only allow that for `demo-*`
 * project ids, which can never be real Firebase projects, so a stray env var cannot open up production.
 */
export function assertSafeEmulatorConfig(env: NodeJS.ProcessEnv = process.env): void {
  const host = env.FIREBASE_AUTH_EMULATOR_HOST;
  if (!host) return;
  const projectId = firebaseProjectId(env);
  if (!projectId?.startsWith('demo-')) {
    throw new Error(
      `FIREBASE_AUTH_EMULATOR_HOST=${host} is set but the Firebase project id is ${projectId ?? 'unset'}; ` +
        'the Auth emulator may only be used with a demo-* project id',
    );
  }
}

/** firebase-admin Auth for the configured project (Application Default Credentials in prod, emulator locally). */
export function firebaseAuth(env: NodeJS.ProcessEnv = process.env): Auth {
  assertSafeEmulatorConfig(env);
  const projectId = firebaseProjectId(env);
  const app = getApps()[0] ?? initializeApp(projectId ? { projectId } : undefined);
  return getAuth(app);
}
