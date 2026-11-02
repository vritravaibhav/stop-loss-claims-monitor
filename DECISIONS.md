# Decisions

## Architecture

| Decision | Why | Alternative considered |
|---|---|---|
| **Flutter web** frontend | Requested stack. One codebase also gives a mobile app later. | React: more common for internal tools, but the team asked for Flutter. |
| **Firebase Authentication** for login; **roles and group access in Postgres** | Firebase does identity (passwords, tokens, later SSO/MFA). Authorisation lives next to the data it protects, so it is one SQL join and can't drift from custom claims. | Firebase custom claims for role/groups: it's one more copy of the truth, and claims only refresh when the token does. |
| **PostgreSQL + SQL migrations** (Flyway), not Firestore | The brief requires a relational database with migrations. The rules are relational and transactional too: unique `(group, claim, version)`, at-most-once alerts as a unique constraint, the per-group lock, sums per member and policy year. Firestore would need these rebuilt by hand. | Firebase Data Connect (managed Postgres with GraphQL): it's relational, but the ingest logic would still need a server, and it adds codegen and less familiar tooling under a tight time box. |
| **Spring Boot API** (Java 25, JdbcTemplate + plain SQL, Flyway) in a container; Cloud Run in prod | The brief says the API, not the UI, must enforce broker access. The same image runs in compose and Cloud Run. Firebase Hosting rewrites `/api/**` to it, so it's same-origin with no CORS in prod. Plain SQL rather than JPA because the rules are set-based (upserts, `DISTINCT ON`, advisory locks), and an ORM would hide them. | Cloud Functions: the same runtime underneath, but a worse fit for 25 MB uploads and long transactions. |
| **The backend was first built in Node/TypeScript, then ported to Spring Boot** | The Node version came first and passed the full end-to-end check. The team's backend stack is Spring, so it was ported, using the Node code and tests as the reference implementation. Both pass the same HTTP-level checker against the same independent reference model, which is what makes the port trustworthy. The Node version is in the git history (`feat/api`). | Keeping Node: less work, but the wrong stack for the team. |
| **Firebase Auth emulator** for local runs | `docker compose up` needs no cloud account, keys or money, and uses the real Firebase SDKs and token verification. | A dev-only "fake login" bypass: faster to build, but it skips the real auth path. |
| **Independent reference model** (`tools/reference_model.py`) + HTTP end-to-end checker | The numbers matter most. A second ~150-line implementation written separately from the API, diffed against it over HTTP, catches rule bugs that tests written next to the code tend to share. | Trusting unit tests alone. |

## Rule interpretations (the "unclear rules" calls)

Files
- **Group comes from the file-name prefix** (`GA_…` → GA), as STOP_LOSS_RULES.md describes. Unknown prefix → the
  whole file is rejected and recorded as a rejected upload.
- **Layout is detected from the header row, not from the group**, and columns are mapped by name rather than by
  position. A file we haven't seen (another group, columns reordered, extra columns) works as long as it uses one of
  the two known header sets. Unknown header → whole file rejected with the header we found.
- **Row number = physical line number in the file** (header and `#` comment lines counted), so "row 18" matches what
  the analyst sees in an editor.
- **Summary rows** (`TRAILER`/`TOTAL`/`SUMMARY` in the first column) are skipped and counted as `summary`, not as
  errors. We don't reconcile the trailer's count or total against the file (see shortcuts).

Row validity: a row is skipped and reported if any of these fail
- Member must exist and belong to the file's group (`A-0999` unknown, `B-0004` in a Group A file).
- Amount: `$` and thousands separators are stripped, and at most 2 decimals are allowed. `N/A`, blank or garbage is
  an error. **Negative amounts are rejected.** Both TPAs signal reversals with a status, so a negative paid amount is
  ambiguous and we surface it rather than guess.
- Dates: `YYYY-MM-DD` and `MM/DD/YYYY` (US) are accepted in either layout and must be real calendar dates. A
  slash date is always read as month-first.
- Status: `PAID`/`P` = paid; `REVERSED`/`R`/`VOID`/`VOIDED`/`V` = reversed (pays $0). Any other status is an error,
  never silently counted.
- Member IDs are trimmed (`" B-0179 "` is `B-0179`).
- Thousands separators must be well-formed: `1234,56` (a decimal comma) is rejected rather than read as $123,456.
- A row with several problems reports all of them, joined with `; `, so the analyst fixes it in one pass.
- Trailing empty cells beyond the header are tolerated; any other column-count mismatch is an error.
- GB files mix line endings (LF after the `#` line, CRLF elsewhere), so lines are split physically before CSV parsing.
- File-name order is plain code-unit order (`GA_2026-04.csv` before `GA_2026-04_resend.csv`). The UI uploads a
  multi-file selection in that order, one file at a time.

Claim versions
- **Every accepted version is stored**, and the current version is the highest number stored. A version older than
  the current one is stored for history but changes nothing (`olderIgnored`).
- "Same values" for rule 6 means all normalised fields: member, service date, paid date, amount, status and
  diagnosis code. Formatting differences (`$1,343.78` vs `1343.78`) are the same value.
- **Conflict (same version, different values): the first-loaded values win** and the row is reported. Changing
  money silently on a resend is exactly what an analyst needs to see.
- Duplicate rows inside one file follow the same rule: an identical duplicate is `unchanged`, a different one is a
  conflict.
- Version numbers only need to be ordered: GA's `Adj Seq` starts at 0 and GB's `version` at 1, and gaps are fine.
- A newer version can move a claim to another member of the same group, or change its service date into another
  policy year. Both the old and the new member/policy-year totals are recomputed and alert-checked.

Spend and alerts
- The deductible is the member's **laser** for that policy year if one exists, else the group's specific deductible.
- A claim whose service date is in no policy year is stored and shown in the drill-down as "outside policy years",
  but counts toward nothing.
- `% used` is shown truncated to one decimal, never rounded up, so 49.96% displays as 49.9% and a displayed 50.0%
  always has an alert.
- "Reaches" means `>=`, checked in integer cents (`spend × 100 >= threshold × deductible`), so exactly 50.00%
  alerts.
- Alerts are checked **once at the end of each file**, inside the same transaction, for every member/policy year the
  file touched. A file that adds a claim and reverses it alerts on the net result, not the intermediate peak.
- At most once per (member, policy year, threshold), enforced by a unique constraint, not just by code.
- Acknowledging needs a non-empty note. Only open alerts can be acknowledged; a second acknowledge is a `409`.
  Acknowledging doesn't stop later thresholds from alerting.

Access
- Brokers see only their brokerage's groups. **Another brokerage's group, member or alert returns `404`, not
  `403`**, so a broker can't probe which IDs exist. Broker calls to write endpoints return `403`.
- Unknown users (valid Firebase token, but no `app_user` row) get `403`. Signing up gives no access.
- Users are matched by email, so the token's email must be **verified** (seeded users are). Otherwise anyone could
  create a Firebase account with someone else's address.
- The API accepts unsigned emulator tokens only when the project id starts with `demo-`, and refuses to start
  otherwise, so a misconfigured production deployment can't fall back to trusting unsigned tokens.

Concurrency and idempotency
- Each upload is one transaction holding a Postgres advisory lock on its group. Two analysts uploading for the same
  group at once are serialised, and a failure rolls back the whole file, so totals are never half-applied.
- Re-uploading a file is processed again rather than short-circuited by hash, and the version rules make it a no-op.
  The sha256 is recorded for audit.

## Optional extras chosen

- **Member drill-down** (every claim and version behind a spend, with the file and row it came from). PERSONAS.md
  says analysts "need to trust the numbers", and it was cheap because every version is already stored.
- **Concurrent-upload safety** (per-group advisory lock). One line of SQL, and it protects the core promise.
- **Audit basics**: who uploaded which file (with sha256) and who acknowledged which alert, when, and with what
  note. These come free from the data model.
- Not done: notification outbox, 200k-row performance work, browser e2e test. The HTTP-level `tools/e2e_check.py`
  covers the main flow without a browser.

## Shortcut list

Each entry covers why it's acceptable now, what could go wrong, what would tell us it's time to fix it, and a rough
cost.

| # | Shortcut | Why acceptable now | What could go wrong | Signal to fix | Rough fix cost |
|---|---|---|---|---|---|
| 1 | **Uploads are processed synchronously** inside the HTTP request, the whole file in memory, in one transaction. | Sample files are ~250 rows; one transaction gives all-or-nothing loads for free. | Big files hit limits: Cloud Run caps HTTP/1 request bodies at 32 MiB, Hosting rewrites time out at 60 s, and heap grows with file size. A 200k-row file would likely time out in prod. | Files over ~5 MB, or p95 upload time over 10 s. | 3–5 days: signed-URL upload to GCS, async worker job, `COPY` into a staging table, set-based merge, upload status polling. |
| 2 | **Spend is summed at read time** from `claim_current`; there's no rollup table. | Indexed, and a few thousand claims sum in milliseconds. A rollup is one more thing that can drift. | Ranking a 50k-member group means summing millions of rows on every page view. | Spend endpoint p95 over 300 ms. | 1–2 days: `member_spend` rollup updated by deltas inside the ingest transaction, plus a rebuild job. |
| 3 | **No pagination**: the spend view returns every member (Group D has 1,000) and the alert list is unbounded. | Fine at this size; the UI filters client-side. | Slow page loads and large payloads at 10k+ members or thousands of alerts. | Groups over ~5k members. | ~1 day: keyset pagination plus server-side search. |
| 4 | **The two TPA layouts are hard-coded.** | Two layouts, both known. Header-based detection already tolerates reordered and extra columns. | A third TPA, or a renamed column, means a code change and a deploy; until then its files are rejected (safely). | Onboarding a new TPA. | 2–4 days: a layout-mapping table (column names, date format, status codes, sign convention) plus an admin screen. |
| 5 | **Slash dates are always read as US `MM/DD/YYYY`; negative amounts are rejected.** | Both known TPAs follow this. | A TPA sending `DD/MM` dates would be **silently misread** whenever the day is 12 or less, which is the dangerous case. Negative adjustments would be rejected, which is safe but noisy. | Any new TPA. | Part of #4 (date format per layout). |
| 6 | **Group is taken from the file-name prefix.** | The rules say the file name starts with the group id. | A misnamed file is rejected. A GB file renamed `GA_…` errors on every row (safe, but confusing). | Analysts renaming files to get them accepted. | ~0.5 day: group picker on the upload screen, pre-filled from the file name. |
| 7 | **Same-version conflicts: the first-loaded values win**, and there's no resolution workflow. | Never silently changes money. The analyst sees exactly which row and fields differ. | If a TPA corrects a claim without bumping the version, our totals stay on the old values until the TPA re-sends it with a new version. | The same TPA producing conflicts repeatedly. | 2–3 days: a conflict queue where an analyst accepts or rejects the new values, with an audit trail. |
| 8 | **Trailer rows are skipped, not reconciled** against the file's row count or total. | Not required by the rules. Every claim row is still validated on its own. | A truncated file loads as a partial month without warning. | The first truncated or partial file incident. | ~0.5 day: compare the trailer's count and total and warn, or reject. |
| 9 | **Reference data (policies, lasers, members) and users come only from the seed.** There's no admin UI, the seed never deletes, and a changed deductible doesn't re-check alerts. | The brief says seeding is fine. | A mid-year laser or deductible change leaves alerts computed against the old deductible. In production there's no way to add a user except SQL plus the Identity Platform console. | The first reference-data change or new broker. | 2–3 days: versioned reference-data upload, an alert re-evaluation job, and user admin endpoints. |
| 10 | **Audit is partial.** We record who uploaded what (with sha256) and who acknowledged which alert, when and with what note. Reads of PHI aren't logged, and the raw files aren't kept (only parsed versions and the raw text of failed rows). | It covers the "who did what" the personas need. | We can't answer "who viewed member X", or re-process an original file after a bug fix. | Any compliance review. | 1–2 days: an access-log table plus raw-file retention in GCS (see PRODUCTION.md). |
| 11 | **No notification outbox**: alerts are only visible in the UI. | Optional extra. | An analyst who doesn't open the app misses a 100% alert. | Any analyst asking for email. | ~1 day: an outbox table written in the ingest transaction, plus a sender job. |
| 12 | **The API is a port.** Node/TS was built first, and the Spring Boot version was ported late under time pressure. | Parity is checked by the same end-to-end checker against the independent reference model, plus a ported test suite. | A behaviour the checker and tests don't exercise could differ between the two versions. | Any prod discrepancy against the reference model. | Ongoing: keep `tools/e2e_check.py` in CI (it is) and grow it with new cases. |
| 13 | **Nothing is deployed, and the target is a shared Firebase project** (`flutter-course-selling`, which already runs another app). Local runs use the Auth emulator. `app/config/prod.json` still has placeholders, and the app refuses to start with them. | The brief doesn't require a deployment, and creating a dedicated project hit the account's GCP quota. Its own Hosting site and `manage_auth_config = false` keep a deploy from touching the other app. | A shared project shares one Firebase Auth user pool, quotas and IAM with the other app. Its users can sign in to Firebase, though our API still gives them `403`. | Before any real data, or as soon as quota allows. | ~0.5 day: a dedicated project per environment, `terraform apply`, fill `app/config/prod.json` from `terraform output`. |
| 14 | **CI, deploy and Terraform have never actually run.** They were checked with `terraform validate`, `actionlint` and `docker compose config`, and the same compose stack the CI docker job runs was run locally and passed the end-to-end check. | The brief explicitly says not to run them. | The first `apply` will probably surface API-enablement ordering or IAM gaps, and WIF needs a real repo id. | First deploy. | 0.5–1 day in a sandbox project. |
| 15 | **Single region; small Cloud SQL tier on a public IP** (connector-only, no authorised networks). | Cheapest setup that keeps the database unreachable except through IAM plus mTLS. | Not acceptable for real PHI: no HA, no private networking, no BAA. | Before any real member data. | See PRODUCTION.md: private IP, regional HA, VPC-SC, CMEK. |
| 16 | **Flutter web loads CanvasKit from Google's CDN.** | It's the default and keeps the image small. | Fails in an air-gapped network or under a strict CSP. | Restricted deployment environments. | Minutes: build with `--no-web-resources-cdn`. |
| 17 | **Alerts never reopen.** Acknowledging is final, and a later threshold raises a new alert instead. | Matches the rules (`open` → `acknowledged`). | Nothing reminds anyone if spend keeps climbing toward the next threshold, except that threshold's own alert. | Analyst feedback. | Small; it's a product decision, not code. |

