output "api_url" {
  description = "Cloud Run URL of the API (also reachable through Hosting at /api/**)."
  value       = google_cloud_run_v2_service.api.uri
}

output "hosting_url" {
  description = "Firebase Hosting URL of the web app."
  value       = google_firebase_hosting_site.web.default_url
}

output "api_image_repository" {
  description = "Push API images here as <repository>/api:<git-sha>."
  value       = "${google_artifact_registry_repository.docker.location}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}

output "cloud_sql_connection_name" {
  description = "For `cloud-sql-proxy <name>` when an operator needs a psql session."
  value       = google_sql_database_instance.main.connection_name
}

output "database_url_secret" {
  description = "Secret Manager secret holding DATABASE_URL."
  value       = google_secret_manager_secret.database_url.id
}

output "migrate_job" {
  description = "Cloud Run Job that runs `npm run migrate`."
  value       = google_cloud_run_v2_job.migrate.name
}

# GitHub repository variables for .github/workflows/deploy.yml
output "github_actions_variables" {
  description = "Set these as GitHub Actions repository variables (Settings > Secrets and variables > Actions > Variables)."
  value = {
    GCP_PROJECT_ID                 = var.project_id
    GCP_REGION                     = var.region
    GCP_WORKLOAD_IDENTITY_PROVIDER = google_iam_workload_identity_pool_provider.github.name
    GCP_DEPLOY_SERVICE_ACCOUNT     = google_service_account.deploy.email
  }
}

# Write to app/config/prod.json: terraform output -json app_prod_config > ../../app/config/prod.json
output "app_prod_config" {
  description = "dart-define values for the production web build (--dart-define-from-file)."
  value = {
    FIREBASE_API_KEY             = data.google_firebase_web_app_config.web.api_key
    FIREBASE_APP_ID              = google_firebase_web_app.web.app_id
    FIREBASE_PROJECT_ID          = var.project_id
    FIREBASE_AUTH_DOMAIN         = data.google_firebase_web_app_config.web.auth_domain
    FIREBASE_MESSAGING_SENDER_ID = data.google_firebase_web_app_config.web.messaging_sender_id
    USE_AUTH_EMULATOR            = "false"
    API_BASE_URL                 = ""
  }
}
