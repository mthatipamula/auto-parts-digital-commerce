variable "project_id" { type = string }
variable "region" {
  type    = string
  default = "us-central1"
}
variable "github_repository" {
  type        = string
  description = "owner/name allowed to deploy"
}
variable "spanner_processing_units" {
  type    = number
  default = 100
}
