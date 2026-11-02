locals {
  services = toset([
    "artifactregistry.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "firebase.googleapis.com",
    "firebasehosting.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",  # service account impersonation via WIF
    "identitytoolkit.googleapis.com", # Firebase Auth / Identity Platform
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "serviceusage.googleapis.com",
    "sqladmin.googleapis.com", # also used by the Cloud SQL connector in Cloud Run
    "sts.googleapis.com",      # WIF token exchange
  ])
}

resource "google_project_service" "enabled" {
  provider = google.bootstrap
  for_each = local.services

  project = var.project_id
  service = each.value

  # Leave APIs on when this stack is destroyed: other things in the project may use them,
  # and disabling an API can cascade-delete resources outside this stack.
  deletion_policy = "ABANDON"
}
