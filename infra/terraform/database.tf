# Cloud SQL for PostgreSQL 16.
#
# Connectivity: public IP with NO authorised networks, ssl_mode
# TRUSTED_CLIENT_CERTIFICATE_REQUIRED. Nothing can open a plain TCP connection to the public
# address: the only way in is a Cloud SQL connector. Here that is the Cloud SQL Java socket
# factory inside the API (com.google.cloud.sql.postgres.SocketFactory), which needs IAM
# roles/cloudsql.client and uses mTLS with an ephemeral client certificate. That avoids a
# VPC, a private service access peering range and VPC egress for Cloud Run. See README for
# the private-IP alternative.

resource "google_sql_database_instance" "main" {
  name                = "stoploss-pg"
  database_version    = "POSTGRES_16"
  region              = var.region
  deletion_protection = var.deletion_protection

  settings {
    # Explicit: shared-core tiers (db-f1-micro, db-g1-small) only exist in ENTERPRISE.
    edition                     = "ENTERPRISE"
    tier                        = var.db_tier
    availability_type           = var.db_availability_type
    disk_type                   = "PD_SSD"
    disk_size                   = var.db_disk_size_gb
    disk_autoresize             = true
    deletion_protection_enabled = var.deletion_protection
    user_labels                 = var.labels

    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "TRUSTED_CLIENT_CERTIFICATE_REQUIRED"
      # no authorized_networks blocks, on purpose
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "03:00"
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = 14
      }
    }

    maintenance_window {
      day          = 7 # Sunday
      hour         = 4 # UTC
      update_track = "stable"
    }

    insights_config {
      query_insights_enabled  = true
      record_client_address   = false
      record_application_tags = false
    }
  }

  depends_on = [google_project_service.enabled]
}

locals {
  # Spring datasource URL. No host: the socket factory looks the instance up through the
  # Cloud SQL Admin API and opens an mTLS tunnel to it. Not a secret (no credentials in it).
  jdbc_url = format(
    "jdbc:postgresql:///%s?cloudSqlInstance=%s&socketFactory=com.google.cloud.sql.postgres.SocketFactory",
    google_sql_database.app.name,
    google_sql_database_instance.main.connection_name,
  )
}

resource "google_sql_database" "app" {
  name     = "stoploss"
  instance = google_sql_database_instance.main.name
}

# Ephemeral: the password exists only for the duration of a plan/apply. It is sent to Cloud
# SQL and Secret Manager through write-only arguments, so it is never stored in state or in
# plan files. Rotation: bump var.db_password_version.
ephemeral "random_password" "db" {
  length  = 32
  special = false # alphanumeric: no quoting or escaping surprises in env vars / JDBC
}

resource "google_sql_user" "app" {
  name                = "stoploss"
  instance            = google_sql_database_instance.main.name
  password_wo         = ephemeral.random_password.db.result
  password_wo_version = var.db_password_version

  # Postgres refuses to drop a role that owns objects; don't let destroy fail on it.
  deletion_policy = "ABANDON"
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "stoploss-db-password"

  replication {
    auto {}
  }

  depends_on = [google_project_service.enabled]
}

# Consumed as SPRING_DATASOURCE_PASSWORD by the API service and the migration job. The URL
# and username are plain env vars (local.jdbc_url, google_sql_user.app.name).
resource "google_secret_manager_secret_version" "db_password" {
  secret                 = google_secret_manager_secret.db_password.id
  secret_data_wo         = ephemeral.random_password.db.result
  secret_data_wo_version = var.db_password_version
}
