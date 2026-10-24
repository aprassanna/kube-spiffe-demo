#!/usr/bin/env bash
# Builds the base runtime image and both service images in Cloud Build and pushes them to
# Artifact Registry. No local Docker daemon is needed.
#
# Required: PROJECT_ID, REGION
# Optional: AR_REPO (default spiffe-demo), IMAGE_TAG (default 0.1.0)
set -euo pipefail

cd "$(dirname "$0")/.."

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:?set REGION, for example us-central1}"
AR_REPO="${AR_REPO:-spiffe-demo}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"

gcloud builds submit --project "$PROJECT_ID" --config cloudbuild.yaml \
  --substitutions="_REGION=$REGION,_REPO=$AR_REPO,_TAG=$IMAGE_TAG"
