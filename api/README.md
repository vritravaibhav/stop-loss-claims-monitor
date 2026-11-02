# Stop-loss API (Spring Boot)

Java 25, Spring Boot 4.0 (Spring MVC, plain SQL on `NamedParameterJdbcTemplate`, no ORM), Flyway, PostgreSQL 16,
Firebase Admin for ID-token verification. The HTTP contract and the process contract (modes, env, image) are in
[`docs/SPEC.md`](../docs/SPEC.md); this file is about working on the code.

This is a port of an earlier Node/TypeScript API (still in git history before commit "api: remove the
Node/TypeScript API"). Same SQL, same schema, same rules; every endpoint was diffed against the Node version on the
same data (same status codes, byte-identical bodies).

## Build and test

```sh
cd api
./mvnw -B verify          # compile + every test; the jar is target/stoploss-api.jar
./mvnw -B package -DskipTests   # just the jar (no Docker needed)
```

The integration tests start `postgres:16-alpine` with Testcontainers, so a Docker daemon must be reachable. On
GitHub's ubuntu runners and Docker Desktop nothing is needed. With **colima**, Testcontainers does not find the
socket by itself; export:

```sh
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock   # the socket path inside the colima VM (for Ryuk)
# only if the Ryuk cleanup container still fails to start: export TESTCONTAINERS_RYUK_DISABLED=true
```

## Run locally

```sh
# Postgres somewhere, plus the Firebase Auth emulator on :9099 (docker compose up db firebase, or
# npx -y firebase-tools@15.34.2 emulators:start --only auth --project demo-stoploss)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/stoploss \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=postgres \
FIREBASE_AUTH_EMULATOR_HOST=localhost:9099 FIREBASE_PROJECT_ID=demo-stoploss \
SEED_ON_STARTUP=true \
java -jar target/stoploss-api.jar                     # migrate, seed (DB + emulator users), serve on :8080

java -jar target/stoploss-api.jar --app.mode=load-sample   # load data/claims in file-name order, then exit
python3 ../tools/e2e_check.py                              # end-to-end check against a fresh database
```

`--app.mode=migrate|seed|load-sample` runs once without a web server and exits 0 (1 on failure).
`DATA_DIR` defaults to `../data`, i.e. the repository's `data/` when run from `api/`.

Image: `docker build -t stoploss-api api` (multi-stage, builds the jar itself; see `Dockerfile`).

## Layout

```
src/main/java/com/example/stoploss/
  domain/     pure rules, no Spring/DB: Csv, ClaimFile (both layouts, bad-row reasons), Money (cents, % used),
              Dates, Versions (new / older_ignored / unchanged / conflict), Alerts (50/75/100), Js (JavaScript string
              semantics the rules were written against: trim/\s whitespace set)
  upload/     IngestService (one transaction per file, advisory lock, versions, claim_current, alerts),
              UploadQueries, UploadResult
  groups/     GET /api/me, /api/groups, /api/groups/{id}/spend, /api/members/{id}
  alerts/     GET /api/alerts, POST /api/alerts/{id}/acknowledge
  web/        uploads, healthz, errors ({"error": ...}), CORS, filters
  auth/       AuthFilter (Bearer → TokenVerifier → app_user), Scope (the visible_group CTE), AppUser
  seed/       ReferenceData (data/*.csv), SeedService, FirebaseUsers, LoadSampleService
  config/     AppSettings (env), FirebaseConfig (emulator guard, firebase-admin)
  AppModes, StoplossApplication   one-shot modes, SEED_ON_STARTUP
src/main/resources/db/migration/  Flyway migrations
```

## Tests

| Class | What |
|---|---|
| `domain/*Test` | parsers for both layouts and every bad-row reason, money/dates, version decisions, alert thresholds (exactly 50% fires, 49.99% doesn't, one file crossing 50/75/100 gives three) |
| `upload/IngestServiceTest` | re-upload idempotent, older version ignored, conflict keeps the first values, reversal → $0 with the alert kept, drop-then-rise raises nothing new, moved claims recompute both placements, out-of-year claims count nowhere, lasers, concurrent uploads |
| `upload/GoldenTest` | all sample files in file-name order: per-file counts, every bad row, alerts per file (23: 14/6/3) |
| `web/HttpApiTest` | MockMvc: 401/403, broker scoping (404 for other brokerages), analyst-only writes, response shapes and key order, acknowledge, uploads |
| `web/RealServerTest` | embedded Tomcat: 25 MB → 413, the broker check runs before the multipart body is parsed |
| `seed/SeedServiceTest`, `config/FirebaseWiringTest`, `HelpersTest` | idempotent seed, the real Firebase wiring and emulator guard, small helpers and modes |

Integration tests share one Spring context and one Postgres container; each resets the claim tables it needs. The
token verifier is replaced by `support/FakeTokenVerifier` (`Bearer test:verified:<email>`), which exists only in the
test sources.
