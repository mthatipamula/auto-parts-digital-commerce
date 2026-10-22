output "registry" { value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}" }
output "valkey_endpoints" { value = google_memorystore_instance.valkey.endpoints }
output "images_bucket" { value = google_storage_bucket.images.name }
output "workload_identity_provider" { value = google_iam_workload_identity_pool_provider.github.name }
output "ci_service_account" { value = google_service_account.ci.email }
output "get_credentials" { value = "gcloud container clusters get-credentials ${google_container_cluster.gke.name} --region ${var.region}" }
