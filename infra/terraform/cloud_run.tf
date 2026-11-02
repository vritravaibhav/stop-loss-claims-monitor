locals {
  cloudsql_mount = "/cloudsql"
}

resource "google_cloud_run_v2_service" "api" {
  name                = "stoploss-api"
  location            = var.region
  deletion_protection = var.deletion_protection

  # Firebase Hosting rewrites reach the service from the public internet, so ingress can't
  # be restricted to internal/load-balancer traffic.
  ingress = "INGRESS_TRAFFIC_ALL"

  template {
    service_account                  = google_service_account.api.email
    max_instance_request_concurrency = var.api_concurrency
    # Upload requests parse and apply a whole file in one transaction.
    timeout = "300s"

    scaling {
      min_instance_count = var.api_min_instances
      max_instance_count = var.api_max_instances
    }

    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [google_sql_database_instance.main.connection_name]
      }
    }

    containers {
      name  = "api"
      image = var.api_image
      # No command/args: the image's default (npm start) serves the API.

      ports {
        container_port = 8080
      }

      env {
        name  = "FIREBASE_PROJECT_ID"
        value = var.project_id
      }
      env {
        name  = "GCLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database_url.secret_id
            version = "latest"
          }
        }
      }

      resources {
        limits = {
          cpu    = var.api_cpu
          memory = var.api_memory
        }
        cpu_idle          = true # CPU billed only while handling requests
        startup_cpu_boost = true
      }

      volume_mounts {
        name       = "cloudsql"
        mount_path = local.cloudsql_mount
      }

      # A revision only receives traffic once /healthz answers (up to ~60s).
      startup_probe {
        http_get {
          path = "/healthz"
          port = 8080
        }
        period_seconds    = 3
        timeout_seconds   = 2
        failure_threshold = 20
      }
    }
  }

  lifecycle {
    # deploy.yml rolls out new images with `gcloud run deploy --image`; Terraform owns the
    # rest of the configuration and must not roll the image back on the next apply.
    ignore_changes = [
      template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  depends_on = [
    google_project_iam_member.api_cloudsql_client,
    google_secret_manager_secret_iam_member.api_database_url,
    google_secret_manager_secret_version.database_url,
  ]
}

# Public invoker: Firebase Hosting rewrites can't attach an IAM identity, so the service must
# accept unauthenticated invocations. Access control is in the API: every /api request needs
# a valid Firebase ID token and an app_user row, and broker scoping is enforced in SQL.
# /healthz is the only unauthenticated route.
resource "google_cloud_run_v2_service_iam_member" "api_public" {
  name     = google_cloud_run_v2_service.api.name
  location = google_cloud_run_v2_service.api.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# Runs `npm run migrate` with the same image and DATABASE_URL. deploy.yml points it at the new
# image and executes it (and waits) before rolling out the service, so the schema is migrated
# before new code serves traffic. Migrations must stay backwards compatible with the
# previous revision (expand/contract), since it keeps serving until the rollout finishes.
resource "google_cloud_run_v2_job" "migrate" {
  name                = "stoploss-migrate"
  location            = var.region
  deletion_protection = false

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account = google_service_account.api.email
      max_retries     = 0 # a failed migration should fail the deploy, not loop
      timeout         = "600s"

      volumes {
        name = "cloudsql"
        cloud_sql_instance {
          instances = [google_sql_database_instance.main.connection_name]
        }
      }

      containers {
        image = var.api_image
        # args replaces the image CMD and keeps its ENTRYPOINT (tini).
        args = ["npm", "run", "migrate"]

        env {
          name = "DATABASE_URL"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.database_url.secret_id
              version = "latest"
            }
          }
        }

        resources {
          limits = {
            cpu    = "1"
            memory = "512Mi"
          }
        }

        volume_mounts {
          name       = "cloudsql"
          mount_path = local.cloudsql_mount
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [
      template[0].template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  depends_on = [
    google_project_iam_member.api_cloudsql_client,
    google_secret_manager_secret_iam_member.api_database_url,
    google_secret_manager_secret_version.database_url,
  ]
}
