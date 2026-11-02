resource "google_artifact_registry_repository" "docker" {
  repository_id = "stoploss"
  location      = var.region
  format        = "DOCKER"
  description   = "stoploss API images (tagged with the git commit SHA)"

  # A tag always means the same image, so a deploy of commit X is reproducible.
  # deploy.yml skips the build when the tag already exists.
  docker_config {
    immutable_tags = true
  }

  # Storage cost control: keep the 10 most recent images (rollback targets), delete
  # untagged layers after a week.
  cleanup_policy_dry_run = false
  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 10
    }
  }
  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"
    condition {
      tag_state  = "UNTAGGED"
      older_than = "604800s"
    }
  }

  depends_on = [google_project_service.enabled]
}
