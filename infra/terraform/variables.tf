variable "project_id" {
  description = "Existing GCP project (with billing enabled) to deploy into. Firebase is added to it."
  type        = string
}

variable "region" {
  description = "Region for Cloud Run, Cloud SQL and Artifact Registry. firebase.json's Cloud Run rewrites name this region too."
  type        = string
  default     = "us-central1"
}

variable "api_image" {
  description = "Initial API image (Spring Boot, ENTRYPOINT java -jar) for the Cloud Run service and migration job, e.g. us-central1-docker.pkg.dev/<project>/stoploss/api:<git-sha>. Later deploys change the image through deploy.yml; Terraform ignores image drift."
  type        = string
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to deploy through Workload Identity Federation."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must look like owner/name."
  }
}

variable "github_deploy_ref" {
  description = "Only workflow runs on this git ref can exchange a GitHub OIDC token for deploy credentials."
  type        = string
  default     = "refs/heads/main"
}

variable "db_tier" {
  description = "Cloud SQL machine tier. db-f1-micro is shared-core (no SLA, max_connections = 25); see README for sizing."
  type        = string
  default     = "db-f1-micro"
}

variable "db_availability_type" {
  description = "ZONAL (single zone) or REGIONAL (HA standby in a second zone, roughly 2x the cost)."
  type        = string
  default     = "ZONAL"

  validation {
    condition     = contains(["ZONAL", "REGIONAL"], var.db_availability_type)
    error_message = "db_availability_type must be ZONAL or REGIONAL."
  }
}

variable "db_disk_size_gb" {
  description = "Initial Cloud SQL disk size; autoresize grows it."
  type        = number
  default     = 10
}

variable "db_password_version" {
  description = "Bump to rotate the database password: a new random password is written to Cloud SQL and to the stoploss-db-password secret in the same apply. Redeploy the API afterwards (the secret is read at revision start)."
  type        = number
  default     = 1
}

variable "deletion_protection" {
  description = "Protect the Cloud SQL instance and the Cloud Run service from deletion (Terraform-side and, for Cloud SQL, API-side)."
  type        = bool
  default     = true
}

variable "api_min_instances" {
  description = "Cloud Run minimum instances. 0 = scale to zero (no idle cost, but every cold start pays JVM + Spring startup)."
  type        = number
  default     = 0
}

variable "api_max_instances" {
  description = "Cloud Run maximum instances. Keep max_instances x pool size (HikariCP default 10) below the DB tier's max_connections."
  type        = number
  default     = 2
}

variable "api_cpu" {
  description = "vCPU per API instance."
  type        = string
  default     = "1"
}

variable "api_memory" {
  description = "Memory per API instance and for the migration job. The image sizes the JVM heap with MaxRAMPercentage=75."
  type        = string
  default     = "1Gi"
}

variable "api_concurrency" {
  description = "Max concurrent requests per API instance."
  type        = number
  default     = 40
}

variable "hosting_site_id" {
  description = "Firebase Hosting site id. Defaults to the project id, i.e. the project's default site (https://<project>.web.app), which firebase deploy targets without extra config."
  type        = string
  default     = null
}

variable "extra_authorized_domains" {
  description = "Extra domains allowed for Firebase Auth redirect/popup flows (e.g. a custom domain)."
  type        = list(string)
  default     = []
}

variable "manage_auth_config" {
  description = "Manage the project's Firebase Auth / Identity Platform config (email/password on, self sign-up off). It is project-wide, so set false when the project is shared with another app whose sign-up must keep working."
  type        = bool
  default     = true
}

variable "labels" {
  description = "Labels applied to every resource that supports them."
  type        = map(string)
  default = {
    app        = "stoploss"
    managed-by = "terraform"
  }
}
