# Build spec & shared contract

This is the contract the three workstreams (api, app, infra) build against in parallel. If you need to change
it, change this file in the same commit and say so in the commit message.

## Stack

| Layer | Choice | Why |
|---|---|---|
| Frontend | Flutter (web target; same code runs on mobile) | requested stack |
| Auth | Firebase Authentication (email/password). Local: Firebase Auth **emulator**, project id `demo-stoploss` | requested stack; emulator means no cloud account needed to run |
| API | Node 22 + TypeScript + Express, container on Cloud Run (prod) | the brief needs the API (not the UI) to enforce access |
| DB | PostgreSQL 16 (Cloud SQL in prod), SQL migrations with `node-pg-migrate` | brief requires a **relational DB with migrations**; Firestore is not relational, so it is not used |
| Tests | vitest + supertest against a real Postgres | version/alert rules are DB-transactional; mocking the DB would test the mock |

## Repository layout and ownership

```
api/            Node/TS API            (owner: api workstream)
  migrations/   node-pg-migrate SQL migrations
  src/          server code
  test/         vitest tests
  scripts/      seed + load-sample-claims
app/            Flutter app            (owner: app workstream)
infra/          terraform/, firebase-emulator/, nginx/   (owner: infra workstream)
.github/        CI                     (owner: infra workstream)
docker-compose.yml, firebase.json, .firebaserc, api/Dockerfile, app/Dockerfile, */.dockerignore   (owner: infra)
data/           reference + sample claim files (read-only, CRLF, byte-for-byte)
docs/SPEC.md    this file
```

## API process contract (infra relies on this)

- `cd api && npm ci && npm run build` → compiles to `api/dist/`.
- `npm run migrate` → runs migrations up (needs `DATABASE_URL`).
- `npm run seed` → idempotent: upserts brokerages, groups, policy years, members, lasers, app users from `DATA_DIR`
  (default `../data`, in Docker `/data`), and if `FIREBASE_AUTH_EMULATOR_HOST` is set (or `SEED_FIREBASE_USERS=true`)
  creates/updates the Firebase Auth users with password `SEED_PASSWORD` (default `Password123!`).
- `npm run load-sample` → uploads every file in `$DATA_DIR/claims` in file-name order through the same ingest code
  as the HTTP upload (dev convenience, not run by default).
- `npm start` → `node dist/src/server.js` (or equivalent), listens on `PORT` (default 8080).
- `npm run start:docker` → migrate, seed, then start (single entrypoint for compose).
- `npm test` → vitest; needs `DATABASE_URL` pointing at a disposable Postgres (tests create/truncate their own schema).
- Env: `DATABASE_URL`, `PORT`, `DATA_DIR`, `FIREBASE_AUTH_EMULATOR_HOST` (e.g. `firebase:9099`),
  `GCLOUD_PROJECT` / `FIREBASE_PROJECT_ID` (`demo-stoploss` locally), `CORS_ORIGINS` (comma list, dev only),
  `SEED_PASSWORD`, `SEED_FIREBASE_USERS`.
- `GET /healthz` → `200 {"ok":true}` (no auth) — used by compose healthcheck and Cloud Run.

## Seed logins (password `Password123!`)

| Email | Role | Brokerage | Groups |
|---|---|---|---|
| analyst@example.test | analyst | – | all |
| broker.a@example.test | broker | Brokerage A | GA |
| broker.b@example.test | broker | Brokerage B | GB |

GC and GD belong to no brokerage (analyst-only).

## Authentication & authorisation

- Client signs in with Firebase Auth, sends `Authorization: Bearer <Firebase ID token>` on every `/api/*` call.
- API verifies the token with `firebase-admin` (`verifyIdToken`; emulator honoured via `FIREBASE_AUTH_EMULATOR_HOST`),
  takes the **email**, and looks the user up in the `app_user` table. The DB row is the source of truth for role and
  brokerage — not client claims. Unknown user → `403`.
- Token verification is injected (`createApp({ verifyToken })`) so tests use a fake verifier; the fake must never be
  reachable in production code paths.
- Broker scoping is applied **in SQL** (every group-scoped query joins through the caller's visible groups). A broker
  asking for another brokerage's group/member/alert gets `404` (do not leak existence). Write endpoints for brokers
  → `403`.

## Business rules — decisions (these go into DECISIONS.md)

Files
- Group comes from the file-name prefix before the first `_` (`GA_2026-04_resend.csv` → `GA`). Unknown group →
  whole file rejected (`400`, recorded as a rejected upload).
- Layout is detected from the **header row**, not from the group (a new group may use either TPA's layout).
  Lines before the header that start with `#` and blank lines are skipped. Header matching is case-insensitive and
  whitespace-trimmed. Unknown header → whole file rejected.
  - Layout `tpa_one` (Group A): `Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount,Status,Diagnosis Code`
  - Layout `tpa_two` (Group B): `claim_number,version,member,dos,paid_dt,amount,claim_status`
- Handle UTF-8 BOM, CRLF/LF, quoted fields (`"14,455.00"`).
- **Row number** reported = physical line number in the file (1-based, header and comment lines counted), i.e.
  what the analyst sees in a text editor.
- Summary rows (first cell `TRAILER`/`TOTAL`/`SUMMARY`, case-insensitive) are skipped and counted as `summary`,
  not errors. Completely empty rows are ignored.

Field parsing (per row; any failure → row error with a human-readable reason, row skipped)
- Claim ID: required, trimmed string.
- Version: required non-negative integer (`Adj Seq` starts at 0, `version` at 1 — only order matters).
- Member ID: trimmed (`" B-0179 "` → `B-0179`). Unknown member → error. Member of another group → error.
- Service date: required. Accept `MM/DD/YYYY` and `YYYY-MM-DD`; must be a real calendar date.
- Paid date: optional; if present must parse like service date.
- Amount: strip `$`, `,`, spaces; must match a decimal with ≤2 dp. `N/A`, blank, garbage → error. Negative amounts
  → error (status is how a TPA signals a reversal; a negative paid amount is ambiguous, so we surface it). Stored as
  integer **cents** (`bigint`) — never floats.
- Status (case-insensitive): paid = `PAID`,`P`; reversed = `REVERSED`,`R`,`VOID`,`VOIDED`,`V`. Anything else → error.
- Diagnosis code: optional, stored as-is.

Claim versions (rule numbers from STOP_LOSS_RULES.md)
- Identity = (group_id, claim_id). Every accepted row is stored as a version in `claim_version`
  (unique `(group_id, claim_id, version)`), so the drill-down can show history.
- Current version = highest version number stored for the claim (rule 3). Effective amount = 0 if the current
  version is reversed/void, else its paid amount (rule 4).
- A version lower than the current one is stored but changes nothing (rule 5) → outcome `older_ignored`.
- Same (claim, version) again with identical normalised values (member, service date, paid date, amount, status,
  diagnosis) → outcome `unchanged` (rule 6), including duplicates inside the same file.
- Same (claim, version) with different values → row error `conflict` (rule 6). The first-loaded values are kept.
- A newer version may move the claim to another member of the same group or another service date; both the old and
  new (member, policy year) totals are recomputed.

Spend & deductibles
- Policy year of a claim = the group's policy year whose `[start, end]` (inclusive) contains the **service date**.
  None → claim counts toward no spend (still stored; shown as "outside policy years" in drill-down).
- Deductible = laser for (member, policy year) if present, else the policy year's specific deductible.
- Spend = sum of effective amounts of current versions in that policy year. `% used = spend / deductible × 100`.
  `excess = max(0, spend − deductible)`.

Alerts
- Thresholds 50, 75, 100. Fires when `spend_cents * 100 >= threshold * deductible_cents` (integer maths, "reaches"
  means `>=`).
- Evaluated once at the **end of each file**, inside the same DB transaction, for every (member, policy year) the
  file touched. One file crossing several thresholds raises one alert per threshold.
- At most once per (member, policy year, threshold): enforced by a unique constraint. Spend dropping later leaves the
  alert; rising again raises nothing.
- Lifecycle: `open` → `acknowledged` (analyst only, non-empty note required; acknowledging a non-open alert → `409`).

Concurrency & idempotency
- Each upload runs in one transaction holding `pg_advisory_xact_lock` keyed on the group id, so two uploads for the
  same group serialise and cannot corrupt totals. Re-uploading the same file is processed again and, by the version
  rules, changes nothing (every row `unchanged`). The file's sha256 is recorded.

## Database (migrations in `api/migrations`)

`brokerage(id, name)` · `employer_group(id text pk, name, brokerage_id null)` · `policy_year(id serial, group_id,
start_date, end_date, specific_deductible_cents bigint, unique(group_id,start_date))` · `member(id text pk, group_id,
first_name, last_name, date_of_birth, relationship)` · `laser(member_id, policy_year_id, specific_deductible_cents,
pk(member_id, policy_year_id))` · `app_user(id serial, email unique, role check in ('analyst','broker'),
brokerage_id null, check broker ⇒ brokerage)` · `upload(id, group_id, filename, sha256, layout, status
('processed','rejected'), uploaded_by, uploaded_at, counts…, rejection_reason)` · `upload_row_error(upload_id,
row_number, reason, raw text)` · `claim_version(id, group_id, claim_id, version, member_id, service_date, paid_date,
paid_amount_cents, status ('paid','reversed'), diagnosis_code, upload_id, row_number, unique(group_id,claim_id,version))`
· `claim_current(group_id, claim_id pk, claim_version_id, member_id, policy_year_id null, effective_amount_cents)` ·
`alert(id, group_id, member_id, policy_year_id, threshold check in (50,75,100), spend_cents_at_trigger,
deductible_cents_at_trigger, upload_id, status ('open','acknowledged'), created_at, acknowledged_by, acknowledged_at,
ack_note, unique(member_id, policy_year_id, threshold))`.

Exact column names are the api workstream's call; the shapes above are what matters.

## HTTP API (all JSON, all under `/api`, all require a Bearer token)

Money in responses is a JSON number of **dollars** with 2 dp (e.g. `18000.00` → `18000`), percentages are numbers
(1 dp), dates are `YYYY-MM-DD`, timestamps ISO-8601 UTC.

```
GET  /api/me
  → { email, role: "analyst"|"broker", brokerage: {id,name}|null }

GET  /api/groups
  → [{ id, name, brokerage: {id,name}|null, memberCount, openAlertCount,
       policyYears: [{ id, startDate, endDate, deductible }], currentPolicyYearId }]
  currentPolicyYearId = the policy year containing today, else the latest one.

GET  /api/groups/:groupId/spend?policyYearId=<id>          (default: currentPolicyYearId)
  → { group: {id,name}, policyYear: {id,startDate,endDate,deductible},
      members: [{ memberId, firstName, lastName, relationship, spend, deductible, hasLaser,
                  pctUsed, excess, claimCount, alertThresholds: [50,75] }] }
  members sorted by pctUsed desc, then memberId. All members of the group are included (zero spend too).

GET  /api/members/:memberId
  → { member: {memberId, groupId, firstName, lastName, dateOfBirth, relationship},
      policyYears: [{ policyYearId, startDate, endDate, deductible, hasLaser, spend, pctUsed, excess }],
      claims: [{ claimId, policyYearId|null, currentVersion, status, effectiveAmount, serviceDate,
                 versions: [{ version, status, paidAmount, serviceDate, paidDate, diagnosisCode,
                              filename, rowNumber, loadedAt, isCurrent }] }] }

GET  /api/alerts?status=open|acknowledged|all&groupId=GA   (default status=open)
  → [{ id, groupId, groupName, memberId, memberName, policyYearId, policyYearStart, policyYearEnd,
       threshold, spendAtTrigger, deductibleAtTrigger, currentSpend, currentPctUsed, status, createdAt,
       uploadFilename, acknowledgedBy, acknowledgedAt, note }]
  newest first (createdAt desc, then threshold desc).

POST /api/alerts/:id/acknowledge   body { note }            analyst only
  → the updated alert. 400 empty note, 403 broker, 404 not visible, 409 not open.

POST /api/uploads   multipart/form-data, field "file"       analyst only
  → 201 UploadResult (also 200 is fine) ; 400 for rejected files (body still an UploadResult with status "rejected")
GET  /api/uploads                                           analyst only, newest first, without row errors
GET  /api/uploads/:id                                       analyst only, UploadResult

UploadResult = { id, filename, groupId, layout, status: "processed"|"rejected", rejectionReason|null,
  uploadedBy, uploadedAt, sha256,
  counts: { rows, applied, unchanged, olderIgnored, summary, errors },
  errors: [{ rowNumber, reason, raw }],
  alertsRaised: [{ id, memberId, threshold, spend, deductible, pctUsed }] }
```

Errors: `{ "error": "<message>" }` with the status codes above; `401` missing/invalid token.

## Frontend contract

- Flutter web, Material 3. Talks to the API at `API_BASE_URL` (dart-define, default `""` = same origin; the web
  container's nginx proxies `/api` to the API and Firebase Hosting rewrites `/api/**` to Cloud Run in prod).
- Firebase config from dart-defines with emulator-friendly defaults: `FIREBASE_API_KEY` (default `demo-api-key`),
  `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID` (default `demo-stoploss`), `FIREBASE_AUTH_DOMAIN`,
  `FIREBASE_MESSAGING_SENDER_ID`, `USE_AUTH_EMULATOR` (default `true`), `AUTH_EMULATOR_HOST` (default `localhost`),
  `AUTH_EMULATOR_PORT` (default `9099`). Prod values live in `app/config/prod.json` for `--dart-define-from-file`.
- Screens: Login · Groups · Group spend (policy-year picker, members ranked by % used, progress bar coloured at
  50/75/100, excess, alert badges) · Member drill-down (claims + all versions) · Alerts (open/acknowledged/all,
  acknowledge-with-note dialog for analysts) · Upload (analyst; multi-file pick, uploaded **sequentially in file-name
  order**, per-file result with failed rows table and alerts raised) · Upload history (analyst).
- Broker UI hides upload/acknowledge, but the API is the enforcement point.

## Local run

`docker compose up --build` → Postgres, Firebase Auth emulator (9099, UI 4000), API (8080, runs migrate + seed),
web (http://localhost:8081, nginx serving the Flutter build and proxying `/api`).
