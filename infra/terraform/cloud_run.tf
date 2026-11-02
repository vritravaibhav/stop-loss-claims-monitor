# Both the service and the migration job run the same image (ENTRYPOINT
# java -jar /app/stoploss-api.jar) and reach Cloud SQL through the Cloud SQL Java socket
# factory named in local.jdbc_url. No Cloud SQL volume is mounted: the socket factory talks
# to the Cloud SQL Admin API itself, authorised by roles/cloudsql.client on the runtime SA.

locals {
  datasource_env = {
    SPRING_DATASOURCE_URL      = local.jdbc_url
    SPRING_DATASOURCE_USERNAME = google_sql_user.app.name
    FIREBASE_PROJECT_ID        = var.project_id
  }
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

    containers {
      name  = "api"
      image = var.api_image
      # No command/args: the image ENTRYPOINT serves the API. Cloud Run sets PORT itself.

      ports {
        container_port = 8080
      }

      dynamic "env" {
        for_each = local.datasource_env
        content {
          name  = env.key
          value = env.value
        }
      }
      env {
        name = "SPRING_DATASOURCE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }
      # Only the stoploss-migrate job migrates. A revision never changes the schema at
      # startup, so several instances starting at once don't all run Flyway, and the
      # runtime could later use a DB role without DDL rights.
      env {
        name  = "SPRING_FLYWAY_ENABLED"
        value = "false"
      }

      resources {
        limits = {
          cpu    = var.api_cpu
          memory = var.api_memory
        }
        cpu_idle          = true # CPU billed only while handling requests
        startup_cpu_boost = true # JVM startup is CPU-bound
      }

      # A revision only receives traffic once /healthz answers (up to ~120s: JVM start,
      # Spring context, connection pool).
      startup_probe {
        http_get {
          path = "/healthz"
          port = 8080
        }
        period_seconds    = 3
        timeout_seconds   = 2
        failure_threshold = 40
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
    google_secret_manager_secret_iam_member.api_db_password,
    google_secret_manager_secret_version.db_password,
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

# Runs Flyway once (--app.mode=migrate) with the same image and credentials, then exits.
# deploy.yml points it at the new image and executes it (and waits) before rolling out the
# service, so the schema is migrated before new code serves traffic. Migrations must stay
# backwards compatible with the previous revision (expand/contract), since it keeps serving
# until the rollout finishes.
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

      containers {
        image = var.api_image
        # Appended to the image ENTRYPOINT: java -jar /app/stoploss-api.jar --app.mode=migrate
        args = ["--app.mode=migrate"]

        dynamic "env" {
          for_each = local.datasource_env
          content {
            name  = env.key
            value = env.value
          }
        }
        env {
          name = "SPRING_DATASOURCE_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.db_password.secret_id
              version = "latest"
            }
          }
        }

        resources {
          limits = {
            cpu    = "1"
            memory = var.api_memory
          }
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
    google_secret_manager_secret_iam_member.api_db_password,
    google_secret_manager_secret_version.db_password,
  ]
}
