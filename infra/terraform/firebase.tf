locals {
  hosting_site_id = coalesce(var.hosting_site_id, var.project_id)
}

# Adds Firebase to the existing GCP project.
resource "google_firebase_project" "default" {
  provider = google-beta
  project  = var.project_id

  depends_on = [google_project_service.enabled]
}

resource "google_firebase_web_app" "web" {
  provider     = google-beta
  project      = google_firebase_project.default.project
  display_name = "Stop-loss claims monitor"
}

# Public client config for app/config/prod.json (see the app_prod_config output). These
# values are not secrets: they ship in every browser bundle.
data "google_firebase_web_app_config" "web" {
  provider   = google-beta
  project    = google_firebase_project.default.project
  web_app_id = google_firebase_web_app.web.app_id
}

resource "google_firebase_hosting_site" "web" {
  provider = google-beta
  project  = google_firebase_project.default.project
  site_id  = local.hosting_site_id
  app_id   = google_firebase_web_app.web.app_id
}

# Firebase Authentication (Identity Platform): email/password only.
resource "google_identity_platform_config" "auth" {
  project = google_firebase_project.default.project

  sign_in {
    allow_duplicate_emails = false
    email {
      enabled           = true
      password_required = true
    }
  }

  # Accounts are provisioned by an administrator, not self-service: the API would reject an
  # unknown user anyway (no app_user row -> 403), but there is no reason to let anyone
  # create Firebase accounts from the browser.
  client {
    permissions {
      disabled_user_signup   = true
      disabled_user_deletion = true
    }
  }

  # Only needed for redirect/popup sign-in flows; listed so adding one later just works.
  authorized_domains = distinct(concat([
    "${var.project_id}.firebaseapp.com",
    "${var.project_id}.web.app",
    "${local.hosting_site_id}.web.app",
  ], var.extra_authorized_domains))

  depends_on = [google_project_service.enabled]
}
