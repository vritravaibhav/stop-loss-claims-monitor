# Running this for 50 insurers, 5M members, ~100M claim rows a year

**Tenancy.** Pooled by default: one schema, `tenant_id` on every table and in every primary/unique key
(`(tenant_id, group_id, claim_id, version)`), and Postgres row-level security keyed on a `SET LOCAL app.tenant_id`
issued by the API per transaction, so a missing `WHERE` cannot leak across insurers. The existing broker scoping
(SQL join through visible groups) stays as a second layer. A tenant registry maps each insurer to a database, so a
large insurer or one whose contract demands it can be siloed onto its own Cloud SQL instance with the same schema and
code. Identity moves from Firebase Auth to **Identity Platform multi-tenancy** (one auth tenant per insurer, SAML/OIDC
SSO for analysts, MFA enforced); roles and group access stay in our DB, not in token claims.

**Security of health data (PHI).** Sign Google Cloud's BAA and use only covered services (Cloud Run, Cloud SQL, GCS,
Identity Platform; static UI only on Hosting/CDN). Cloud SQL on private IP inside a VPC Service Controls perimeter,
CMEK on SQL, GCS and backups, TLS everywhere, secrets in Secret Manager, no service-account keys (Workload Identity
Federation for CI). Least privilege: no human has standing prod DB access; break-glass is time-boxed and audited.
Application audit log of every PHI read and write (who viewed which member, uploaded which file, acknowledged which
alert), shipped to an append-only sink with retention. No PHI in logs or traces: log member/claim IDs only as
salted hashes, never names, DOB, diagnosis codes or raw rows. Raw TPA files land in a locked-down bucket with
retention and lifecycle deletion; DLP scan on ingest. Data minimisation: brokers don't need DOB or diagnosis codes,
so the API doesn't return them to brokers.

**Ingest at scale.** 100M rows/yr is ~3 rows/s on average but arrives in monthly bursts of multi-million-row files.
Replace the synchronous HTTP upload with: signed-URL upload to GCS → Eventarc/Pub/Sub → a Cloud Run **job** per file.
The worker streams the CSV, `COPY`s parsed rows into an unlogged staging table, then applies the version rules as
set-based SQL (`INSERT … ON CONFLICT`, conflict and older-version detection by join) instead of row-at-a-time.
Keep the per-group advisory lock and the file sha256 for idempotency. Maintain a `member_spend` rollup updated by
deltas inside the same transaction (instead of re-summing), and evaluate alerts only for the members whose rollup
changed. Upload status becomes asynchronous (pending → processed) with the row-error report stored per file. TPA
layouts move from code to a per-TPA column-mapping config so onboarding an insurer is data, not a deploy.

**Data layout & scaling.** `claim_version` partitioned by tenant and service-year (declarative partitioning; old
partitions detached to cheaper storage after the retention window); `claim_current` and `member_spend` indexed on
`(tenant_id, group_id, policy_year_id, pct_used desc)` to make "members ranked by closeness" a top-N query with
pagination (no endpoint returns 5M rows). Cloud SQL regional HA, read replicas for dashboards and broker reads,
managed connection pooling (Cloud SQL connection pooling or PgBouncer) because Cloud Run scales horizontally.
API stays stateless and autoscaled; ingest workers scale independently so a burst of uploads never slows the UI.

**Observability.** OpenTelemetry traces and metrics (Cloud Trace / Managed Prometheus), structured JSON logs with
request and upload IDs. Business SLOs, not just CPU: file processed within 15 min of upload, row-error rate per TPA,
"expected monthly file missing" per group, alert volume anomalies, ingest lag, p95 API latency. Pages go to on-call
for SLO burn; data-quality dashboards go to the analysts.

**Deployment.** Terraform per environment (dev / staging / prod projects) with remote state in GCS and plans
reviewed in PRs; CI builds once and promotes the same image digest. Migrations run as a gated Cloud Run job using
expand/contract (backward-compatible first, cleanup later) so old and new API versions can run side by side; Cloud
Run traffic splitting for canaries with automatic rollback on SLO regression. PITR backups with restore drills, a
documented DR region, and per-tenant feature flags so rule changes (e.g. a new threshold) can roll out per insurer.
