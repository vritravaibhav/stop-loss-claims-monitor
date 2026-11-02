# --- Runtime identity: Cloud Run service + migration job ---------------------------------

resource "google_service_account" "api" {
  account_id   = "stoploss-api"
  display_name = "stoploss API runtime (Cloud Run service and migration job)"

  depends_on = [google_project_service.enabled]
}

# Connect through the Cloud SQL connector. cloudsql.client is project-level (Cloud SQL has
# no per-instance IAM bindings); it grants connect, not admin.
resource "google_project_iam_member" "api_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = google_service_account.api.member
}

# Read only the DATABASE_URL secret, not every secret in the project.
resource "google_secret_manager_secret_iam_member" "api_database_url" {
  secret_id = google_secret_manager_secret.database_url.id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.api.member
}

# --- Deploy identity: GitHub Actions (deploy.yml) via Workload Identity Federation -------
# No service account keys: GitHub's OIDC token is exchanged for short-lived credentials,
# and only runs from var.github_repository on var.github_deploy_ref are accepted.

resource "google_service_account" "deploy" {
  account_id   = "stoploss-deploy"
  display_name = "stoploss GitHub Actions deployer"

  depends_on = [google_project_service.enabled]
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"

  depends_on = [google_project_service.enabled]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-oidc"
  display_name                       = "GitHub OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  attribute_condition = "assertion.repository == \"${var.github_repository}\" && assertion.ref == \"${var.github_deploy_ref}\""

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "deploy_wif" {
  service_account_id = google_service_account.deploy.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

# Push images to this repository only.
resource "google_artifact_registry_repository_iam_member" "deploy_push" {
  location   = google_artifact_registry_repository.docker.location
  repository = google_artifact_registry_repository.docker.name
  role       = "roles/artifactregistry.writer"
  member     = google_service_account.deploy.member
}

# Deploy service revisions, update and execute the migration job.
resource "google_project_iam_member" "deploy_run" {
  project = var.project_id
  role    = "roles/run.developer"
  member  = google_service_account.deploy.member
}

# Deploying a revision that runs as the API service account requires actAs on it.
resource "google_service_account_iam_member" "deploy_act_as_api" {
  service_account_id = google_service_account.api.name
  role               = "roles/iam.serviceAccountUser"
  member             = google_service_account.deploy.member
}

resource "google_project_iam_member" "deploy_hosting" {
  project = var.project_id
  role    = "roles/firebasehosting.admin"
  member  = google_service_account.deploy.member
}

# firebase-tools checks that the APIs it needs are enabled.
resource "google_project_iam_member" "deploy_service_usage" {
  project = var.project_id
  role    = "roles/serviceusage.serviceUsageConsumer"
  member  = google_service_account.deploy.member
}
