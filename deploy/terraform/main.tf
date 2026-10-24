# GCP foundation for auto-parts-digital-commerce. Review costs in deploy/README.md before `terraform apply`.
terraform {
  required_version = ">= 1.9"
  required_providers {
    google = { source = "hashicorp/google", version = "~> 7.0" }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

locals {
  services = {
    gateway = { roles = [] }
    # spanner.viewer lets a service see whether its schema exists yet (SpannerSetup applies it on first start).
    catalog   = { roles = ["roles/spanner.databaseUser", "roles/spanner.viewer", "roles/storage.objectAdmin"] }
    inventory = { roles = ["roles/spanner.databaseUser", "roles/spanner.viewer", "roles/pubsub.subscriber"] }
    checkout  = { roles = ["roles/spanner.databaseUser", "roles/spanner.viewer", "roles/pubsub.publisher", "roles/pubsub.subscriber"] }
    payment   = { roles = [] }
    account   = { roles = ["roles/spanner.databaseUser", "roles/spanner.viewer", "roles/pubsub.subscriber"] }
  }
  k8s_sa = {
    gateway  = "gateway", catalog = "catalog-service", inventory = "inventory-service",
    checkout = "checkout-service", payment = "payment-service", account = "account-service"
  }
  apis = ["container.googleapis.com", "spanner.googleapis.com", "memorystore.googleapis.com", "pubsub.googleapis.com",
    "storage.googleapis.com", "artifactregistry.googleapis.com", "cloudbuild.googleapis.com", "iam.googleapis.com", "iamcredentials.googleapis.com", "compute.googleapis.com",
  "networkconnectivity.googleapis.com", "serviceconsumermanagement.googleapis.com"]
}

resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.value
  disable_on_destroy = false
}

# ---------------------------------------------------------------- network

resource "google_compute_network" "vpc" {
  name                    = "commerce"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.apis]
}

resource "google_compute_subnetwork" "gke" {
  name                     = "gke"
  network                  = google_compute_network.vpc.id
  ip_cidr_range            = "10.20.0.0/20"
  private_ip_google_access = true
}

# Private nodes have no public IPs: Cloud NAT gives them outbound access to pull public images (Keycloak, Grafana).
resource "google_compute_router" "nat" {
  name    = "commerce-nat"
  network = google_compute_network.vpc.id
  region  = var.region
}

resource "google_compute_router_nat" "nat" {
  name                               = "commerce-nat"
  router                             = google_compute_router.nat.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# ---------------------------------------------------------------- GKE Autopilot (Workload Identity on by default)

resource "google_container_cluster" "gke" {
  name                = "commerce"
  location            = var.region
  enable_autopilot    = true
  network             = google_compute_network.vpc.id
  subnetwork          = google_compute_subnetwork.gke.id
  deletion_protection = true
  ip_allocation_policy {}
  release_channel { channel = "REGULAR" }
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
  }
}

# ---------------------------------------------------------------- Spanner

resource "google_spanner_instance" "commerce" {
  name             = "commerce"
  config           = "regional-${var.region}"
  display_name     = "commerce"
  processing_units = var.spanner_processing_units # 100 PU is the smallest; scale with load
  edition          = "STANDARD"
  depends_on       = [google_project_service.apis]
}

resource "google_spanner_database" "db" {
  for_each                 = toset(["catalog", "inventory", "checkout", "accounts"])
  instance                 = google_spanner_instance.commerce.name
  name                     = each.value
  deletion_protection      = true
  version_retention_period = "1h" # point-in-time recovery window
  # Schema is applied by each service on first start (see SpannerSetup); DDL here would also work.
}

# ---------------------------------------------------------------- Memorystore for Valkey (private, via PSC)

resource "google_network_connectivity_service_connection_policy" "valkey" {
  name          = "valkey-psc"
  location      = var.region
  service_class = "gcp-memorystore"
  network       = google_compute_network.vpc.id
  psc_config { subnetworks = [google_compute_subnetwork.gke.id] }
  depends_on = [google_project_service.apis]
}

resource "google_memorystore_instance" "valkey" {
  instance_id                 = "commerce-cache"
  location                    = var.region
  shard_count                 = 1
  replica_count               = 1 # a replica in another zone for failover
  node_type                   = "SHARED_CORE_NANO"
  engine_version              = "VALKEY_8_0"
  mode                        = "CLUSTER_DISABLED"
  transit_encryption_mode     = "TRANSIT_ENCRYPTION_DISABLED" # traffic stays on the private PSC endpoint
  authorization_mode          = "AUTH_DISABLED"
  deletion_protection_enabled = true
  desired_auto_created_endpoints {
    network    = google_compute_network.vpc.id
    project_id = var.project_id
  }
  depends_on = [google_network_connectivity_service_connection_policy.valkey]
}

# ---------------------------------------------------------------- Cloud Storage (product images)

resource "google_storage_bucket" "images" {
  name                        = "${var.project_id}-product-images"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "inherited"
  versioning { enabled = true }
  lifecycle_rule {
    condition { num_newer_versions = 3 }
    action { type = "Delete" }
  }
}

# The catalog seeder checks the bucket exists before uploading images; objectAdmin alone can't read bucket metadata.
resource "google_storage_bucket_iam_member" "images_catalog_bucket_reader" {
  bucket = google_storage_bucket.images.name
  role   = "roles/storage.legacyBucketReader"
  member = "serviceAccount:${google_service_account.svc["catalog"].email}"
}

resource "google_storage_bucket_iam_member" "images_public" {
  bucket = google_storage_bucket.images.name
  role   = "roles/storage.objectViewer"
  member = "allUsers" # product images are public content; put Cloud CDN in front for production
}

# ---------------------------------------------------------------- Pub/Sub with dead-letter topics

resource "google_pubsub_topic" "order_events" {
  name                       = "order-events"
  message_retention_duration = "604800s"
  depends_on                 = [google_project_service.apis]
}

resource "google_pubsub_topic" "dead_letter" {
  name = "order-events-dlq"
}

resource "google_pubsub_subscription" "consumers" {
  for_each                = toset(["inventory.order-events", "checkout.order-history", "account.order-events"])
  name                    = each.value
  topic                   = google_pubsub_topic.order_events.id
  enable_message_ordering = true
  ack_deadline_seconds    = 30
  retry_policy {
    minimum_backoff = "1s"
    maximum_backoff = "60s"
  }
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dead_letter.id
    max_delivery_attempts = 10 # poison messages go to the DLQ instead of blocking the ordering key forever
  }
}

data "google_project" "this" {}

# Pub/Sub's own service agent must be able to move messages to the dead-letter topic.
resource "google_pubsub_topic_iam_member" "dlq_publisher" {
  topic  = google_pubsub_topic.dead_letter.id
  role   = "roles/pubsub.publisher"
  member = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}

resource "google_pubsub_subscription_iam_member" "dlq_subscriber" {
  for_each     = google_pubsub_subscription.consumers
  subscription = each.value.id
  role         = "roles/pubsub.subscriber"
  member       = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}

# ---------------------------------------------------------------- per-service identities (Workload Identity)

resource "google_service_account" "svc" {
  for_each     = local.services
  account_id   = each.key
  display_name = "${each.key} service"
}

resource "google_project_iam_member" "svc_roles" {
  for_each = { for pair in flatten([for s, cfg in local.services : [for r in cfg.roles : { key = "${s}-${r}", svc = s, role = r }]]) : pair.key => pair }
  project  = var.project_id
  role     = each.value.role
  member   = "serviceAccount:${google_service_account.svc[each.value.svc].email}"
}

resource "google_service_account_iam_member" "workload_identity" {
  for_each           = local.services
  service_account_id = google_service_account.svc[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[commerce/${local.k8s_sa[each.key]}]"
  depends_on         = [google_container_cluster.gke] # the PROJECT.svc.id.goog pool exists only once the cluster does
}

# ---------------------------------------------------------------- images + keyless CI

resource "google_artifact_registry_repository" "images" {
  repository_id = "commerce"
  format        = "DOCKER"
  location      = var.region
  depends_on    = [google_project_service.apis]
  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions { keep_count = 30 }
  }
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  attribute_condition = "assertion.repository == '${var.github_repository}' && assertion.ref == 'refs/heads/main'"
  oidc { issuer_uri = "https://token.actions.githubusercontent.com" }
}

resource "google_service_account" "ci" {
  account_id   = "commerce-ci"
  display_name = "GitHub Actions: build and deploy"
}

resource "google_service_account_iam_member" "ci_wif" {
  service_account_id = google_service_account.ci.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

resource "google_project_iam_member" "ci_roles" {
  for_each = toset(["roles/artifactregistry.writer", "roles/container.developer"])
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.ci.email}"
}
