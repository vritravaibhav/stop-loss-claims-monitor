# Terraform: GCP + Firebase deployment

Infrastructure as code for running the stop-loss monitor on Google Cloud. **It has not been applied**: no plan or
apply has been run against a real project. It has been checked with `terraform fmt -check -recursive`,
`terraform init -backend=false` and `terraform validate` (Terraform 1.17.1 and OpenTofu 1.13.5), and the same checks
run in CI (`.github/workflows/ci.yml`, job `terraform`).

The project id `stoploss-monitor-dv` in `.firebaserc` and `terraform.tfvars.example` is a **placeholder**. Creating
that project failed because both available accounts were at their GCP project quota, so nothing exists behind it.
Put the real project id in both places before applying.

## What it creates

```
browser ──► Firebase Hosting (https://<site>.web.app)
              ├─ static Flutter web build (app/build/web)
              └─ rewrites /api/** and /healthz ──► Cloud Run service "stoploss-api" (Spring Boot)
                                                        │  (Cloud SQL Java socket factory: IAM + mTLS)
                                                        ▼
                                                   Cloud SQL Postgres 16 "stoploss-pg"
browser ──► Firebase Auth (Identity Platform): email/password, ID token sent as Bearer to the API
```

| File | Resources |
|---|---|
| `apis.tf` | `google_project_service` for Run, Cloud SQL Admin, Secret Manager, Artifact Registry, IAM/STS (WIF), Firebase, Hosting, Identity Toolkit. Left enabled on destroy. |
| `registry.tf` | Artifact Registry Docker repo `stoploss`: immutable tags (the git SHA), keeps the last 10 images, deletes untagged layers after 7 days. |
| `database.tf` | Cloud SQL `POSTGRES_16` (ENTERPRISE edition, `db-f1-micro` by default, PITR backups, deletion protection), database `stoploss`, user `stoploss`, Secret Manager secret `stoploss-db-password` (the user's password; the JDBC URL and username aren't secret and are plain env vars). |
| `iam.tf` | `stoploss-api` runtime service account with **only** `roles/cloudsql.client` + `secretAccessor` on that one secret. `stoploss-deploy` service account for GitHub Actions, plus a Workload Identity Federation pool/provider that accepts only this repo's runs on `main`. |
| `cloud_run.tf` | `google_cloud_run_v2_service` `stoploss-api`: `SPRING_DATASOURCE_URL` (socket-factory JDBC URL), `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` from Secret Manager, `FIREBASE_PROJECT_ID`, `SPRING_FLYWAY_ENABLED=false`, 1 GiB, scale 0..`api_max_instances`, `/healthz` startup probe of up to ~120 s, public invoker. `google_cloud_run_v2_job` `stoploss-migrate`: same image, env and SA, run with `--app.mode=migrate` (Flyway). |
| `firebase.tf` | `google_firebase_project`, `google_firebase_web_app` (and its public config), `google_firebase_hosting_site`, `google_identity_platform_config` (email/password only, self sign-up disabled). |
| `outputs.tf` | API/Hosting URLs, image repository, the GitHub Actions variables for `deploy.yml`, and `app_prod_config` (the contents of `app/config/prod.json`). |

## Choices

**Cloud Run + Cloud SQL, not GKE/VMs.** One stateless container scales to zero when idle, deploys are revisions with
instant rollback, and there is no cluster to patch. The API holds no state besides the database.

**Cloud SQL over public IP with no authorised networks, plus a Cloud SQL connector, not private IP.** With zero
authorised networks and `ssl_mode = TRUSTED_CLIENT_CERTIFICATE_REQUIRED`, nothing can open a direct TCP connection to
the public address. The only way in is a connector: here the Cloud SQL Java socket factory on the API's classpath
(`jdbc:postgresql:///stoploss?cloudSqlInstance=…&socketFactory=com.google.cloud.sql.postgres.SocketFactory`). It
checks IAM (`roles/cloudsql.client`) on every connection and uses mTLS with ephemeral certificates, so Cloud Run
needs no Cloud SQL volume or sidecar. Private IP
would also need a VPC, a private-service-access peering range and Direct VPC egress (or a connector) on Cloud Run:
more resources and IP planning, for a small demo whose access is already IAM-gated. For real health data I would still
move to private IP (see below), because "no public endpoint at all" is easier to argue in an audit.

**The DB password never lands in state.** `random_password` is an *ephemeral* resource and reaches Cloud SQL and
Secret Manager only through write-only arguments (`password_wo`, `secret_data_wo`). That is why `required_version` is
`>= 1.11`. The API reads the secret as `SPRING_DATASOURCE_PASSWORD`. To rotate it, bump `db_password_version`: both
get the same new value in one apply, then redeploy the API (Cloud Run reads `latest` when a revision starts).

**Only the migration job migrates.** The service runs with `SPRING_FLYWAY_ENABLED=false`. Several instances starting
at once therefore never race Flyway, a revision never changes the schema as a side effect of scaling up, and the
runtime could later use a DB role without DDL rights. `deploy.yml` runs `stoploss-migrate` (`--app.mode=migrate`) and
waits for it before rolling out the service. Locally, compose keeps Flyway-on-startup for a one-command run.

**Public invoker (`allUsers`).** Firebase Hosting rewrites can't attach an IAM identity, so the service must accept
unauthenticated invocations. Authorisation is the API's job: every `/api` request needs a valid Firebase ID token,
the email must exist in `app_user`, and broker scoping is enforced in SQL. Only `/healthz` is open.

**Same origin in prod and locally.** Hosting rewrites `/api/**` to Cloud Run, just as nginx proxies `/api` in docker
compose. The browser never makes a cross-origin call, so there is no CORS configuration in prod and
`API_BASE_URL` is `""` in both places.

**Terraform owns configuration, CD owns the image.** The service and job set `ignore_changes` on the container image,
so `terraform apply` never rolls back a release made by `deploy.yml` (`gcloud run deploy --image`).

**No service-account keys.** GitHub Actions authenticates through Workload Identity Federation. The provider's
`attribute_condition` accepts only `var.github_repository` on `var.github_deploy_ref`.

## Order of operations

Prerequisites: an existing project with billing enabled (Hosting rewrites to Cloud Run and Identity Platform need the
Blaze/billing plan), `gcloud auth application-default login`, an identity with Owner (or equivalent) on the project,
Docker, and Terraform or OpenTofu >= 1.11.

1. **State** (once): create a versioned GCS bucket and uncomment the `backend "gcs"` block in `versions.tf`
   (instructions inline), then `terraform init`.
2. **Variables**: `cp terraform.tfvars.example terraform.tfvars` and set `project_id`, `github_repository`.
3. **Bootstrap the registry.** The Cloud Run service needs an image that already exists:
   `terraform apply -target=google_artifact_registry_repository.docker` (this also enables the APIs).
4. **Push a first image.** `api/Dockerfile` builds the jar with Maven inside the image, so no local JDK is needed.
   Cloud Run runs linux/amd64 only, so on Apple Silicon pass `--platform`:
   ```sh
   REPO=$(terraform output -raw api_image_repository)   # or <region>-docker.pkg.dev/<project>/stoploss
   gcloud auth configure-docker us-central1-docker.pkg.dev
   docker build --platform linux/amd64 -t "$REPO/api:$(git rev-parse HEAD)" ../../api
   docker push "$REPO/api:$(git rev-parse HEAD)"
   ```
   Set `api_image` in `terraform.tfvars` to that tag.
5. **Apply everything**: `terraform apply`.
6. **Migrate**: `gcloud run jobs execute stoploss-migrate --region us-central1 --wait` (Flyway via
   `--app.mode=migrate`). The service doesn't migrate on its own, so do this before the first request.
7. **Users**: there is no seed in prod (`SEED_ON_STARTUP` is unset and `--app.mode=seed` reads the sample data).
   Create Identity Platform users (console or Admin SDK) and their `app_user` rows (see "before running it for real").
8. **Web config**: `terraform output -json app_prod_config > ../../app/config/prod.json` and commit it. The values
   are public by design: they ship in the JS bundle.
9. **CD**: put `terraform output github_actions_variables` into the repository's Actions *variables*, create a
   `production` environment (add required reviewers), then run the **Deploy** workflow. It builds and pushes the API
   image, runs the migration job, deploys the Cloud Run revision, builds Flutter with `config/prod.json` and runs
   `firebase deploy --only hosting`.

Gotchas:
- If the project's default Hosting site already exists (e.g. Hosting was opened in the console), import it rather
  than creating it: `terraform import google_firebase_hosting_site.web projects/<project>/sites/<project>`.
- `firebase.json` names the Cloud Run region (`us-central1`). Change it together with `var.region`.
- Cloud SQL instance names can't be reused for about a week after deletion, and WIF pool ids stay reserved for
  30 days (soft delete).
- Turning on Identity Platform upgrades the project's Firebase Auth, and that can't be undone.
- An org policy restricting `allUsers` (domain-restricted sharing) blocks the public invoker binding.
- `.terraform.lock.hcl` is Terraform's (`registry.terraform.io`, linux/darwin amd64+arm64). `tofu init` adds its own
  `registry.opentofu.org` entries to it.

## Cost (idle, us-central1, approximate list prices; check the pricing calculator)

| Item | Rough monthly cost |
|---|---|
| Cloud SQL `db-f1-micro`, 10 GB SSD, backups + PITR logs | ~$10–15 (the main fixed cost; `REGIONAL` roughly doubles it) |
| Cloud Run, min instances 0, request-based CPU | ~$0 at demo traffic (free tier: 2M requests, 180k vCPU-s) |
| Artifact Registry (10 images kept, a few hundred MB each with the JRE) | about $1 or less |
| Secret Manager (1 secret, few versions) | cents |
| Firebase Hosting | free tier (10 GB stored, 360 MB/day transfer) |
| Identity Platform, email/password | free below 50k MAU |

With scale-to-zero, the first request after an idle period waits for JVM and Spring startup (seconds; the startup
probe allows up to ~120 s). Setting `api_min_instances = 1` removes that but adds roughly the cost of one
always-allocated 1 GiB instance (tens of dollars a month). Class-data sharing (CDS) or a native image would also
shorten cold starts.

## What I'd change before running it for real

1. **State and environments**: GCS backend with versioning and a tightly scoped bucket. Separate projects for
   dev/staging/prod, with this directory turned into a module plus per-env tfvars. Add a billing budget alert.
2. **Database**: private IP + Direct VPC egress; `REGIONAL` availability; a dedicated-core tier (`db-custom-2-7680`
   or larger). Size connections deliberately: `api_max_instances × pool size` (HikariCP defaults to 10) must stay
   under `max_connections` (25 on `db-f1-micro`), which is why `api_max_instances` defaults to 2. Use CMEK if
   required.
3. **No passwords at all**: Cloud SQL IAM database authentication (the Java socket factory supports
   `enableIamAuth=true`), which removes the password secret, and separate
   roles: a migrator service account and DB role that can run DDL, and a runtime role limited to DML. Today the job
   and the service share one SA and one DB user. Set or disable the built-in `postgres` user's password.
4. **Uploads**: Cloud Run caps HTTP/1 request bodies at 32 MiB, and Hosting → Cloud Run rewrites time out at 60 s,
   so prod accepts smaller files than the 50 MB local nginx limit. For large files (200k rows): signed-URL upload
   to GCS, then process in a Cloud Run Job or Tasks worker, with the client polling the upload status.
5. **Health data (HIPAA)**: sign Google's BAA and route PHI only through covered services. Check that list for
   Firebase Hosting. If it isn't covered, serve the API from its own domain on an external HTTPS load balancer
   (serverless NEG, Cloud Armor, `ingress = internal-and-cloud-load-balancing`) and keep Hosting for static files
   only. Also require MFA in Identity Platform, set audit log retention, and use VPC-SC.
6. **Edge protection**: Hosting has no WAF or rate limiting. Put Cloud Armor behind a load balancer, or at least
   rate-limit sign-in and upload in the API.
7. **API key**: restrict the Firebase browser API key (`google_apikeys_key`) to the Identity Toolkit and Secure Token
   APIs and to the Hosting referrers.
8. **Observability**: an uptime check on `/healthz`, alert policies for 5xx rate, latency, instance count, DB CPU,
   storage and connections, and log-based metrics for rejected uploads and new alerts.
9. **Supply chain**: deploy by image digest, turn on Artifact Registry vulnerability scanning, consider Binary
   Authorization. Use `repository_id`/`repository_owner_id` in the WIF condition instead of the repository name,
   which can be renamed or squatted.
10. **User provisioning**: a small admin command (or analyst-only endpoint) that creates the Identity Platform user
    and the `app_user` row together, since self sign-up is disabled and nothing seeds prod.
