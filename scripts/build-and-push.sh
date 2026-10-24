#!/usr/bin/env bash
# Builds both service images and pushes them to Google Artifact Registry.
#
# Required: PROJECT_ID, REGION
# Optional: AR_REPO (default spiffe-demo), IMAGE_TAG (default 0.1.0)
set -euo pipefail

cd "$(dirname "$0")/.."

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:?set REGION, for example us-central1}"
AR_REPO="${AR_REPO:-spiffe-demo}"
IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
AR_PREFIX="$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO"

gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet

for svc in cart-service checkout-service; do
  image="$AR_PREFIX/$svc:$IMAGE_TAG"
  echo "Building and pushing $image"
  # linux/amd64 matches the GKE and Compute Engine node images used in this demo.
  docker build --platform linux/amd64 -t "$image" "services/$svc"
  docker push "$image"
done
