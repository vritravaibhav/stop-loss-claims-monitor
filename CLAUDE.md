# Agent rules for this repo

Stop-loss claims monitor: Flutter web + Firebase Auth + Spring Boot (Java 25) API + PostgreSQL. Read
`docs/SPEC.md` first; it is the contract between workstreams and records the business-rule decisions.

## Ground rules

- Stay inside the directories your workstream owns (see SPEC "Repository layout and ownership"). If the contract
  must change, edit `docs/SPEC.md` in the same commit.
- `data/` is read-only and byte-for-byte (CRLF). Never rewrite or reformat those files.
- Money is integer cents (`long`) end-to-end in the API and DB. No `double`/`float` for amounts; dollars only at the
  JSON edge.
- Business rules live in plain functions with unit tests (parsing, version decision, alert thresholds); DB code calls
  them. Any new rule interpretation goes into SPEC "Business rules — decisions".
- Access control is enforced in SQL in the API. The UI hiding a button is never the control.
- Commit small and often with real messages (`api: ...`, `app: ...`, `infra: ...`). Never squash, never force-push.
- Don't add dependencies you don't need. Prefer the standard library / well-known packages.

## Commands

- API: `cd api && ./mvnw -B verify` (all tests; Testcontainers starts Postgres, so Docker must be running; with
  colima see `api/README.md` for the two env vars). Run: `java -jar api/target/stoploss-api.jar` (needs
  `SPRING_DATASOURCE_URL`); one-shot jobs: `--app.mode=migrate|seed|load-sample`.
- App: `cd app && flutter pub get && flutter analyze && flutter test`.
- Everything: `docker compose up --build` → http://localhost:8081.
