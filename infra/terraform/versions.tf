terraform {
  # 1.11+: ephemeral resources and write-only arguments (keeps the DB password out of
  # state). Works with Terraform >= 1.11 and OpenTofu >= 1.11.
  required_version = ">= 1.11"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.9"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 8.9"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }

  # Remote state. Local state is fine for `validate`, but a real apply must use a shared,
  # locked, versioned backend. To enable:
  #   1. gcloud storage buckets create gs://<project_id>-tfstate --location=<region> \
  #        --uniform-bucket-level-access --public-access-prevention
  #      gcloud storage buckets update gs://<project_id>-tfstate --versioning
  #   2. uncomment the block below and set the bucket name
  #   3. terraform init -migrate-state
  # backend "gcs" {
  #   bucket = "<project_id>-tfstate"
  #   prefix = "stoploss/prod"
  # }
}
