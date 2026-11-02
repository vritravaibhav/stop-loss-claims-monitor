import { describe, expect, it } from 'vitest';
import { parseCorsOrigins } from '../../src/app.js';
import { assertSafeEmulatorConfig, firebaseProjectId } from '../../src/firebase.js';
import { parseId } from '../../src/http.js';
import { currentPolicyYearId } from '../../src/routes/groups.js';

describe('currentPolicyYearId', () => {
  const years = [
    { id: 1, startDate: '2025-07-01', endDate: '2026-06-30', deductibleCents: 1 },
    { id: 2, startDate: '2026-07-01', endDate: '2027-06-30', deductibleCents: 1 },
  ];
  it('picks the year containing today (inclusive bounds)', () => {
    expect(currentPolicyYearId(years, '2026-06-30')).toBe(1);
    expect(currentPolicyYearId(years, '2026-07-01')).toBe(2);
  });
  it('falls back to the latest year', () => {
    expect(currentPolicyYearId(years, '2030-01-01')).toBe(2);
    expect(currentPolicyYearId(years, '2020-01-01')).toBe(2);
    expect(currentPolicyYearId([], '2026-01-01')).toBeNull();
  });
});

describe('Firebase emulator guard', () => {
  it('allows the emulator only with a demo-* project id', () => {
    expect(() => assertSafeEmulatorConfig({ FIREBASE_AUTH_EMULATOR_HOST: 'firebase:9099', FIREBASE_PROJECT_ID: 'demo-stoploss' })).not.toThrow();
    expect(() => assertSafeEmulatorConfig({ FIREBASE_AUTH_EMULATOR_HOST: 'firebase:9099', GCLOUD_PROJECT: 'acme-prod' })).toThrow(/demo-\*/);
    expect(() => assertSafeEmulatorConfig({ FIREBASE_AUTH_EMULATOR_HOST: 'firebase:9099' })).toThrow(/unset/);
    expect(() => assertSafeEmulatorConfig({ GCLOUD_PROJECT: 'acme-prod' })).not.toThrow();
  });
  it('reads the project id from FIREBASE_PROJECT_ID, then GCLOUD_PROJECT, then GOOGLE_CLOUD_PROJECT', () => {
    expect(firebaseProjectId({ GCLOUD_PROJECT: 'b', GOOGLE_CLOUD_PROJECT: 'c' })).toBe('b');
    expect(firebaseProjectId({ FIREBASE_PROJECT_ID: 'a', GCLOUD_PROJECT: 'b' })).toBe('a');
    expect(firebaseProjectId({})).toBeUndefined();
  });
});

describe('small parsers', () => {
  it('parseId accepts positive integers only', () => {
    expect(parseId('42')).toBe(42);
    for (const bad of ['0', '-1', '1.5', '1e3', 'abc', '', ' 1', '0012', undefined]) expect(parseId(bad)).toBeNull();
  });
  it('parseCorsOrigins splits and trims a comma list', () => {
    expect(parseCorsOrigins(' http://localhost:8081, http://127.0.0.1:8081 ,')).toEqual([
      'http://localhost:8081',
      'http://127.0.0.1:8081',
    ]);
    expect(parseCorsOrigins(undefined)).toEqual([]);
  });
});
