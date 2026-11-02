# Agent rules for this repo

Stop-loss claims monitor: Flutter web + Firebase Auth + Node/TS API + PostgreSQL. Read `docs/SPEC.md` first; it is
the contract between workstreams and records the business-rule decisions.

## Ground rules

- Stay inside the directories your workstream owns (see SPEC "Repository layout and ownership"). If the contract
  must change, edit `docs/SPEC.md` in the same commit.
- `data/` is read-only and byte-for-byte (CRLF). Never rewrite or reformat those files.
- Money is integer cents end-to-end in the API and DB. No floats for amounts, no `parseFloat` on money.
- Business rules live in plain functions with unit tests (parsing, version decision, alert thresholds); DB code calls
  them. Any new rule interpretation goes into SPEC "Business rules — decisions".
- Access control is enforced in SQL in the API. The UI hiding a button is never the control.
- Commit small and often with real messages (`api: ...`, `app: ...`, `infra: ...`). Never squash, never force-push.
- Don't add dependencies you don't need. Prefer the standard library / well-known packages.

## Commands

- API: `cd api && npm ci && npm run build && npm test` (needs `DATABASE_URL`, e.g.
  `postgres://postgres:postgres@localhost:5432/stoploss_test`).
- App: `cd app && flutter pub get && flutter analyze && flutter test`.
- Everything: `docker compose up --build` → http://localhost:8081.
