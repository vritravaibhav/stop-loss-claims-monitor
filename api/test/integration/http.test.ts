/**
 * HTTP layer through supertest against a real Postgres: authentication, broker scoping (enforced in SQL), analyst-only
 * writes, response shapes and the acknowledge / upload flows. All sample files are loaded first.
 */
import request from 'supertest';
import type { Express } from 'express';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app.js';
import { loadSampleClaims } from '../../src/services/loadSample.js';
import { ANALYST, BROKER_A, BROKER_B, bearer, fakeVerifyToken } from '../helpers/fakeAuth.js';
import { CLAIMS_DIR, GA_HEADER, setupTestDb, type TestDb } from '../helpers/db.js';

const TODAY = '2026-11-02';
let t: TestDb;
let app: Express;

beforeAll(async () => {
  t = await setupTestDb('http');
  await loadSampleClaims(t.db, CLAIMS_DIR, 'load-sample');
  app = createApp({ db: t.db, verifyToken: fakeVerifyToken, today: () => TODAY });
});
afterAll(async () => {
  await t?.close();
});

const get = (path: string, email: string | null = ANALYST) => {
  const r = request(app).get(path);
  return email ? r.set('Authorization', bearer(email)) : r;
};
const ack = (id: number | string, body: unknown, email = ANALYST) =>
  request(app).post(`/api/alerts/${id}/acknowledge`).set('Authorization', bearer(email)).send(body as object);
const uploadText = (name: string, lines: string[], email = ANALYST) =>
  request(app)
    .post('/api/uploads')
    .set('Authorization', bearer(email))
    .attach('file', Buffer.from(lines.join('\r\n') + '\r\n'), { filename: name, contentType: 'text/csv' });
const uploadSample = (name: string, email = ANALYST) =>
  request(app).post('/api/uploads').set('Authorization', bearer(email)).attach('file', `${CLAIMS_DIR}/${name}`);

interface AlertJson {
  id: number;
  groupId: string;
  memberId: string;
  threshold: number;
  status: string;
  createdAt: string;
  [k: string]: unknown;
}
const allAlerts = async (email = ANALYST): Promise<AlertJson[]> => (await get('/api/alerts?status=all', email)).body;

describe('authentication', () => {
  it('GET /healthz needs no token', async () => {
    const r = await request(app).get('/healthz');
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ ok: true });
  });

  it('401 without a bearer token, with a malformed header, or with a token the verifier rejects', async () => {
    const none = await request(app).get('/api/groups');
    expect(none.status).toBe(401);
    expect(none.body).toEqual({ error: expect.any(String) });
    expect(none.headers['www-authenticate']).toMatch(/^Bearer/);
    expect((await request(app).get('/api/groups').set('Authorization', 'Basic YWJjOmRlZg==')).status).toBe(401);
    expect((await request(app).get('/api/groups').set('Authorization', 'Bearer')).status).toBe(401);
    expect((await request(app).get('/api/groups').set('Authorization', 'Bearer not-a-valid-token')).status).toBe(401);
    // every /api route, including unknown ones and writes, is behind the token check
    expect((await request(app).get('/api/nope')).status).toBe(401);
    expect((await request(app).post('/api/uploads')).status).toBe(401);
    expect((await request(app).post('/api/alerts/1/acknowledge').send({ note: 'x' })).status).toBe(401);
  });

  it('403 for a valid token whose email is unknown, unverified, or missing', async () => {
    expect((await get('/api/me', 'stranger@example.test')).status).toBe(403);
    const unverified = await request(app).get('/api/me').set('Authorization', bearer(ANALYST, false));
    expect(unverified.status).toBe(403);
    expect(unverified.body.error).toMatch(/not verified/);
    expect((await request(app).get('/api/me').set('Authorization', 'Bearer test:verified:')).status).toBe(403);
  });

  it('GET /api/me returns the role and brokerage from the database (email matched case-insensitively)', async () => {
    expect((await get('/api/me', 'Analyst@Example.TEST')).body).toEqual({ email: ANALYST, role: 'analyst', brokerage: null });
    expect((await get('/api/me', BROKER_A)).body).toEqual({
      email: BROKER_A,
      role: 'broker',
      brokerage: { id: 'brokerage-a', name: 'Brokerage A' },
    });
  });

  it('JSON errors, no caching, unknown routes 404', async () => {
    const r = await get('/api/does-not-exist');
    expect(r.status).toBe(404);
    expect(r.body).toEqual({ error: 'Not found' });
    expect((await get('/api/groups')).headers['cache-control']).toBe('no-store');
  });
});

describe('broker scoping (read endpoints)', () => {
  it('GET /api/groups: analyst sees every group, each broker only their brokerage', async () => {
    const all = await get('/api/groups');
    expect(all.status).toBe(200);
    expect(all.body.map((g: { id: string }) => g.id)).toEqual(['GA', 'GB', 'GC', 'GD']);
    expect((await get('/api/groups', BROKER_A)).body.map((g: { id: string }) => g.id)).toEqual(['GA']);
    expect((await get('/api/groups', BROKER_B)).body.map((g: { id: string }) => g.id)).toEqual(['GB']);
  });

  it('GET /api/groups shape: brokerage, counts, policy years in dollars, current policy year', async () => {
    const groups = (await get('/api/groups')).body as Record<string, any>[];
    const gb = groups.find((g) => g.id === 'GB')!;
    expect(gb).toMatchObject({
      id: 'GB',
      name: 'Group B',
      brokerage: { id: 'brokerage-b', name: 'Brokerage B' },
      memberCount: 250,
      openAlertCount: 7,
    });
    expect(gb.policyYears).toEqual([
      { id: expect.any(Number), startDate: '2025-07-01', endDate: '2026-06-30', deductible: 75000 },
      { id: expect.any(Number), startDate: '2026-07-01', endDate: '2027-06-30', deductible: 80000 },
    ]);
    expect(gb.currentPolicyYearId).toBe(gb.policyYears[1].id); // 2026-11-02 is in 2026-27
    const gc = groups.find((g) => g.id === 'GC')!;
    expect(gc).toMatchObject({ brokerage: null, memberCount: 200, openAlertCount: 0 });
    expect(groups.find((g) => g.id === 'GA')).toMatchObject({ memberCount: 300, openAlertCount: 16 });

    // no policy year contains "today" → the latest one
    const later = createApp({ db: t.db, verifyToken: fakeVerifyToken, today: () => '2031-01-01' });
    const gbLater = (await request(later).get('/api/groups').set('Authorization', bearer(ANALYST))).body.find(
      (g: { id: string }) => g.id === 'GB',
    );
    expect(gbLater.currentPolicyYearId).toBe(gb.policyYears[1].id);
  });

  it("a broker gets 404 for another brokerage's (or an analyst-only) group, member and alerts", async () => {
    for (const path of ['/api/groups/GB/spend', '/api/groups/GC/spend', '/api/members/B-0002', '/api/members/C-0011']) {
      const r = await get(path, BROKER_A);
      expect(r.status, path).toBe(404);
    }
    expect((await get('/api/alerts?groupId=GB', BROKER_A)).status).toBe(404);
    // ... exactly like something that does not exist
    expect((await get('/api/groups/NOPE/spend', BROKER_A)).status).toBe(404);
    expect((await get('/api/members/A-9999', BROKER_A)).status).toBe(404);
    // own group works
    expect((await get('/api/groups/GA/spend', BROKER_A)).status).toBe(200);
    expect((await get('/api/members/A-0003', BROKER_A)).status).toBe(200);
    expect((await get('/api/members/B-0002', BROKER_B)).status).toBe(200);
    expect((await get('/api/members/B-0002')).status).toBe(200);
  });

  it('a policy year of another group is 404; a malformed policyYearId is 400', async () => {
    const gb = (await get('/api/groups')).body.find((g: { id: string }) => g.id === 'GB');
    expect((await get(`/api/groups/GA/spend?policyYearId=${gb.policyYears[0].id}`)).status).toBe(404);
    expect((await get('/api/groups/GA/spend?policyYearId=abc')).status).toBe(400);
    expect((await get('/api/groups/GA/spend?policyYearId=999999')).status).toBe(404);
  });

  it('GET /api/alerts is scoped to visible groups', async () => {
    const a = await allAlerts(BROKER_A);
    const b = await allAlerts(BROKER_B);
    expect(a).toHaveLength(16);
    expect(a.every((x) => x.groupId === 'GA')).toBe(true);
    expect(b).toHaveLength(7);
    expect(b.every((x) => x.groupId === 'GB')).toBe(true);
    expect(await allAlerts()).toHaveLength(23);
    expect((await get('/api/alerts?status=all&groupId=GB')).body).toHaveLength(7);
  });

  it('a broker whose brokerage has no groups sees nothing', async () => {
    await t.db.query(`INSERT INTO brokerage (id, name) VALUES ('brokerage-c', 'Brokerage C') ON CONFLICT DO NOTHING`);
    await t.db.query(
      `INSERT INTO app_user (email, role, brokerage_id) VALUES ('broker.c@example.test', 'broker', 'brokerage-c')
       ON CONFLICT DO NOTHING`,
    );
    expect((await get('/api/groups', 'broker.c@example.test')).body).toEqual([]);
    expect(await allAlerts('broker.c@example.test')).toEqual([]);
    expect((await get('/api/groups/GA/spend', 'broker.c@example.test')).status).toBe(404);
  });
});

describe('spend and drill-down', () => {
  it('GET /api/groups/GA/spend: every member, ranked by % used, money in dollars, laser applied', async () => {
    const r = await get('/api/groups/GA/spend');
    expect(r.status).toBe(200);
    expect(r.body.group).toEqual({ id: 'GA', name: 'Group A' });
    expect(r.body.policyYear).toMatchObject({ startDate: '2026-01-01', endDate: '2026-12-31', deductible: 50000 });
    const members = r.body.members as Record<string, any>[];
    expect(members).toHaveLength(300);
    expect(members[0]).toEqual({
      memberId: 'A-0005',
      firstName: expect.any(String),
      lastName: expect.any(String),
      relationship: expect.any(String),
      spend: 56000,
      deductible: 50000,
      hasLaser: false,
      pctUsed: 112,
      excess: 6000,
      claimCount: expect.any(Number),
      alertThresholds: [50, 75, 100],
    });
    expect(members.find((m) => m.memberId === 'A-0007')).toMatchObject({
      spend: 65000,
      deductible: 120000,
      hasLaser: true,
      pctUsed: 54.1, // truncated, not rounded
      excess: 0,
      alertThresholds: [50],
    });
    expect(members.find((m) => m.memberId === 'A-0009')).toMatchObject({ alertThresholds: [50] });
    for (let i = 1; i < members.length; i++) {
      const [p, c] = [members[i - 1]!, members[i]!];
      expect(p.pctUsed > c.pctUsed || (p.pctUsed === c.pctUsed && p.memberId < c.memberId)).toBe(true);
    }
    expect(members.at(-1)).toMatchObject({ spend: 0, pctUsed: 0, claimCount: 0, alertThresholds: [] });
  });

  it('GB spend defaults to the current policy year and can be switched to 2025-26', async () => {
    const current = (await get('/api/groups/GB/spend', BROKER_B)).body;
    expect(current.policyYear).toMatchObject({ startDate: '2026-07-01', deductible: 80000 });
    expect(current.members[0]).toMatchObject({ memberId: 'B-0010', spend: 58000, pctUsed: 72.5 });
    const gb = (await get('/api/groups')).body.find((g: { id: string }) => g.id === 'GB');
    const prior = (await get(`/api/groups/GB/spend?policyYearId=${gb.policyYears[0].id}`, BROKER_B)).body;
    expect(prior.policyYear.startDate).toBe('2025-07-01');
    expect(prior.members[0]).toMatchObject({ memberId: 'B-0006', spend: 60000, pctUsed: 80, alertThresholds: [50, 75] });
    expect(prior.members.find((m: { memberId: string }) => m.memberId === 'B-0008')).toMatchObject({ spend: 0 });
  });

  it('GET /api/members/A-0013: claim 101991 with all three versions, v2 current, late v1 ignored', async () => {
    const r = await get('/api/members/A-0013', BROKER_A);
    expect(r.status).toBe(200);
    expect(r.body.member).toMatchObject({ memberId: 'A-0013', groupId: 'GA' });
    expect(r.body.member.dateOfBirth).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(r.body.policyYears).toEqual([
      expect.objectContaining({ startDate: '2026-01-01', endDate: '2026-12-31', deductible: 50000, hasLaser: false }),
    ]);
    const claim = r.body.claims.find((c: { claimId: string }) => c.claimId === '101991');
    expect(claim).toMatchObject({
      claimId: '101991',
      policyYearId: r.body.policyYears[0].policyYearId,
      currentVersion: 2,
      status: 'paid',
      effectiveAmount: 18000,
      serviceDate: '2026-03-09',
    });
    expect(
      claim.versions.map((v: Record<string, unknown>) => [v.version, v.paidAmount, v.filename, v.rowNumber, v.isCurrent]),
    ).toEqual([
      [2, 18000, 'GA_2026-05.csv', 9, true],
      [1, 25000, 'GA_2026-06.csv', 85, false],
      [0, 9000, 'GA_2026-03.csv', 174, false],
    ]);
    expect(claim.versions[0]).toMatchObject({ status: 'paid', serviceDate: '2026-03-09', paidDate: expect.any(String) });
    expect(claim.versions[0].loadedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });

  it('GET /api/members/B-0008: claim moved to 2026-27 by a newer version; both years shown', async () => {
    const r = (await get('/api/members/B-0008')).body;
    expect(r.policyYears.map((y: Record<string, unknown>) => [y.startDate, y.spend, y.deductible])).toEqual([
      ['2025-07-01', 0, 75000],
      ['2026-07-01', 45000, 80000],
    ]);
    const claim = r.claims.find((c: { claimId: string }) => c.claimId === '7701086');
    expect(claim.policyYearId).toBe(r.policyYears[1].policyYearId);
    expect(claim.serviceDate).toBe('2026-07-01');
    expect(claim.versions.map((v: { serviceDate: string }) => v.serviceDate)).toContain('2026-06-30');
  });

  it('a reversed claim shows status reversed with effective amount 0 (A-0009, claim 101988)', async () => {
    const r = (await get('/api/members/A-0009')).body;
    expect(r.claims.find((c: { claimId: string }) => c.claimId === '101988')).toMatchObject({
      status: 'reversed',
      effectiveAmount: 0,
      currentVersion: 1,
    });
  });
});

describe('alerts list', () => {
  it('default status is open; newest first, then threshold desc; SPEC fields', async () => {
    const open = (await get('/api/alerts')).body as AlertJson[];
    expect(open).toHaveLength(23);
    expect(open[0]).toMatchObject({ memberId: 'B-0010', threshold: 50, uploadFilename: 'GB_2026-09.csv' });
    for (let i = 1; i < open.length; i++) {
      const [p, c] = [open[i - 1]!, open[i]!];
      expect(p.createdAt > c.createdAt || (p.createdAt === c.createdAt && p.threshold >= c.threshold)).toBe(true);
    }
    const a7 = open.find((a) => a.memberId === 'A-0007')!;
    expect(a7).toEqual({
      id: expect.any(Number),
      groupId: 'GA',
      groupName: 'Group A',
      memberId: 'A-0007',
      memberName: expect.stringMatching(/^\S+ \S+/),
      policyYearId: expect.any(Number),
      policyYearStart: '2026-01-01',
      policyYearEnd: '2026-12-31',
      threshold: 50,
      spendAtTrigger: expect.any(Number),
      deductibleAtTrigger: 120000,
      currentSpend: 65000,
      currentPctUsed: 54.1,
      status: 'open',
      createdAt: expect.stringMatching(/Z$/),
      uploadFilename: 'GA_2026-06.csv',
      acknowledgedBy: null,
      acknowledgedAt: null,
      note: null,
    });
    // the alert stays after spend drops to 0
    expect(open.find((a) => a.memberId === 'B-0012')).toMatchObject({ threshold: 50, currentSpend: 0, currentPctUsed: 0 });
  });

  it('rejects an unknown status filter', async () => {
    expect((await get('/api/alerts?status=closed')).status).toBe(400);
  });
});

describe('acknowledge (analyst only)', () => {
  it('brokers get 403, for their own alerts and for other brokerages; nothing changes', async () => {
    const gaAlert = (await allAlerts()).find((a) => a.groupId === 'GA')!;
    const gbAlert = (await allAlerts()).find((a) => a.groupId === 'GB')!;
    const own = await ack(gaAlert.id, { note: 'mine' }, BROKER_A);
    expect(own.status).toBe(403);
    expect(own.body).toEqual({ error: expect.any(String) });
    expect([403, 404]).toContain((await ack(gbAlert.id, { note: 'not mine' }, BROKER_A)).status);
    expect((await ack(999_999, { note: 'x' }, BROKER_A)).status).toBe(403);
    expect((await allAlerts()).filter((a) => a.status !== 'open')).toEqual([]);
  });

  it('400 for a missing, empty or blank note; 404 for unknown ids', async () => {
    const id = (await allAlerts()).find((a) => a.groupId === 'GA')!.id;
    expect((await ack(id, { note: '' })).status).toBe(400);
    expect((await ack(id, { note: '   ' })).status).toBe(400);
    expect((await ack(id, {})).status).toBe(400);
    expect((await ack(id, { note: 42 })).status).toBe(400);
    expect((await ack(id, { note: 'x'.repeat(2001) })).status).toBe(400);
    expect((await ack(999_999, { note: 'x' })).status).toBe(404);
    expect((await ack('abc', { note: 'x' })).status).toBe(404);
    const malformed = await request(app)
      .post(`/api/alerts/${id}/acknowledge`)
      .set('Authorization', bearer(ANALYST))
      .set('Content-Type', 'application/json')
      .send('{"note":');
    expect(malformed.status).toBe(400);
  });

  it('acknowledges once with a note; the second time is 409; the change shows in lists and counts', async () => {
    const target = (await allAlerts()).find((a) => a.memberId === 'A-0011' && a.threshold === 100)!;
    const r = await ack(target.id, { note: '  Case manager assigned  ' });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({
      id: target.id,
      status: 'acknowledged',
      acknowledgedBy: ANALYST,
      acknowledgedAt: expect.stringMatching(/Z$/),
      note: 'Case manager assigned',
      memberId: 'A-0011',
      threshold: 100,
    });
    const again = await ack(target.id, { note: 'again' });
    expect(again.status).toBe(409);
    expect((await get('/api/alerts')).body).toHaveLength(22);
    expect((await get('/api/alerts?status=acknowledged', BROKER_A)).body.map((a: AlertJson) => a.id)).toEqual([target.id]);
    expect((await get('/api/alerts?status=acknowledged', BROKER_B)).body).toEqual([]);
    const ga = (await get('/api/groups')).body.find((g: { id: string }) => g.id === 'GA');
    expect(ga.openAlertCount).toBe(15);
  });

  it('two concurrent acknowledgements: exactly one wins, the other gets 409', async () => {
    const target = (await get('/api/alerts?groupId=GB')).body[0] as AlertJson;
    const results = await Promise.all([ack(target.id, { note: 'first' }), ack(target.id, { note: 'second' })]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 409]);
  });
});

describe('uploads (analyst only)', () => {
  it('brokers cannot upload or read upload history (403), and nothing is recorded', async () => {
    const before = (await get('/api/uploads')).body.length;
    const r = await uploadSample('GB_2026-04.csv', BROKER_B);
    expect(r.status).toBe(403);
    expect((await get('/api/uploads', BROKER_A)).status).toBe(403);
    expect((await get('/api/uploads/1', BROKER_A)).status).toBe(403);
    expect((await get('/api/uploads')).body.length).toBe(before);
  });

  it('re-uploading a sample file: 201, nothing applied, no alerts, spend identical', async () => {
    const before = (await get('/api/groups/GA/spend')).body;
    const r = await uploadSample('GA_2026-09.csv');
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({
      filename: 'GA_2026-09.csv',
      groupId: 'GA',
      layout: 'tpa_one',
      status: 'processed',
      rejectionReason: null,
      uploadedBy: ANALYST,
      counts: { rows: 269, applied: 0, unchanged: 268, olderIgnored: 0, summary: 1, errors: 0 },
      errors: [],
      alertsRaised: [],
    });
    expect(r.body.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect((await get('/api/groups/GA/spend')).body).toEqual(before);
  });

  it('a new claim reaching exactly 50% raises one alert, returned with the upload result', async () => {
    const members = (await get('/api/groups/GA/spend')).body.members as { memberId: string; spend: number }[];
    const idle = members.filter((m) => m.spend === 0).at(-1)!.memberId;
    const r = await uploadText('GA_2026-10_extra.csv', [
      '# extra test file',
      GA_HEADER,
      `990001,0,${idle},10/01/2026,10/15/2026,"$25,000.00",PAID,I10`,
      `990002,0,${idle},10/02/2026,10/15/2026,N/A,PAID,I10`,
      'TRAILER,2,,,,,,',
    ]);
    expect(r.status).toBe(201);
    expect(r.body.counts).toEqual({ rows: 3, applied: 1, unchanged: 0, olderIgnored: 0, summary: 1, errors: 1 });
    expect(r.body.errors).toEqual([{ rowNumber: 4, reason: expect.stringMatching(/N\/A/), raw: expect.stringContaining('990002') }]);
    expect(r.body.alertsRaised).toEqual([
      { id: expect.any(Number), memberId: idle, threshold: 50, spend: 25000, deductible: 50000, pctUsed: 50 },
    ]);
    const alert = (await get('/api/alerts?groupId=GA')).body.find((a: AlertJson) => a.memberId === idle);
    expect(alert).toMatchObject({ threshold: 50, uploadFilename: 'GA_2026-10_extra.csv', status: 'open' });
  });

  it('rejected files: 400 with an UploadResult body (unknown group prefix, unrecognised header)', async () => {
    const unknownGroup = await uploadText('GZ_2026-01.csv', [GA_HEADER, '1,0,A-0001,01/01/2026,,1.00,PAID,']);
    expect(unknownGroup.status).toBe(400);
    expect(unknownGroup.body).toMatchObject({ status: 'rejected', groupId: null, rejectionReason: expect.stringMatching(/Unknown group "GZ"/) });
    const badHeader = await uploadText('GA_weird.csv', ['foo,bar,baz', '1,2,3']);
    expect(badHeader.status).toBe(400);
    expect(badHeader.body).toMatchObject({ status: 'rejected', groupId: 'GA', layout: null, counts: { rows: 0 } });
  });

  it('400 when no file is attached or the field name is wrong', async () => {
    const none = await request(app).post('/api/uploads').set('Authorization', bearer(ANALYST)).send({});
    expect(none.status).toBe(400);
    const wrongField = await request(app)
      .post('/api/uploads')
      .set('Authorization', bearer(ANALYST))
      .attach('upload', Buffer.from(GA_HEADER + '\r\n'), 'GA_x.csv');
    expect(wrongField.status).toBe(400);
    expect(wrongField.body.error).toMatch(/"file"/);
  });

  it('GET /api/uploads lists newest first without row errors; GET /api/uploads/:id has them', async () => {
    const list = (await get('/api/uploads')).body as Record<string, any>[];
    expect(list[0]).toMatchObject({ filename: 'GA_weird.csv', status: 'rejected' });
    expect(list.every((u) => !('errors' in u))).toBe(true);
    const ga02 = list.find((u) => u.filename === 'GA_2026-02.csv')!;
    expect(ga02.uploadedBy).toBe('load-sample');
    const detail = await get(`/api/uploads/${ga02.id}`);
    expect(detail.status).toBe(200);
    expect(detail.body.errors).toEqual([{ rowNumber: 45, reason: 'Unknown member "A-0999"', raw: expect.any(String) }]);
    expect((await get('/api/uploads/999999')).status).toBe(404);
    expect((await get('/api/uploads/abc')).status).toBe(404);
  });
});

describe('CORS (dev only)', () => {
  it('is off by default and allows only the configured origins', async () => {
    const plain = await request(app).options('/api/groups').set('Origin', 'http://localhost:8081');
    expect(plain.headers['access-control-allow-origin']).toBeUndefined();
    const dev = createApp({ db: t.db, verifyToken: fakeVerifyToken, corsOrigins: ['http://localhost:8081'] });
    const ok = await request(dev)
      .options('/api/groups')
      .set('Origin', 'http://localhost:8081')
      .set('Access-Control-Request-Method', 'GET')
      .set('Access-Control-Request-Headers', 'authorization');
    expect(ok.status).toBe(204);
    expect(ok.headers['access-control-allow-origin']).toBe('http://localhost:8081');
    const other = await request(dev).options('/api/groups').set('Origin', 'http://evil.example');
    expect(other.headers['access-control-allow-origin']).toBeUndefined();
  });
});
