# Firebase Management and Identity Platform APIs need a quota ("billing") project on every
# call. user_project_override sends var.project_id as that project, which is what the
# Firebase Terraform guide recommends. Enabling the APIs themselves must not depend on an
# API that isn't on yet, so google_project_service uses the "bootstrap" alias without it.

provider "google" {
  project               = var.project_id
  region                = var.region
  user_project_override = true
  billing_project       = var.project_id
  default_labels        = var.labels
}

provider "google-beta" {
  project               = var.project_id
  region                = var.region
  user_project_override = true
  billing_project       = var.project_id
  default_labels        = var.labels
}

provider "google" {
  alias                 = "bootstrap"
  project               = var.project_id
  region                = var.region
  user_project_override = false
}
