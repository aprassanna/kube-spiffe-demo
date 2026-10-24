#!/usr/bin/env bash
# Deploys SPIRE and both services to the current kubectl context.
#
# Usage: scripts/deploy.sh gke|kubeadm
# Required: PROJECT_ID, REGION
# Optional: AR_REPO (default spiffe-demo), IMAGE_TAG is set in the manifests (0.1.0)
set -euo pipefail

cd "$(dirname "$0")/.."

TARGET="${1:?usage: scripts/deploy.sh gke|kubeadm}"
case "$TARGET" in
  gke|kubeadm) ;;
  *) echo "unknown target: $TARGET (use gke or kubeadm)" >&2; exit 2 ;;
esac

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:?set REGION, for example us-central1}"
AR_REPO="${AR_REPO:-spiffe-demo}"
AR_PREFIX="$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO"

echo "Context: $(kubectl config current-context)  target: $TARGET  images: $AR_PREFIX"

# 1. SPIRE server, then the trust bundle it publishes.
kubectl apply -f deploy/spire/spire-server.yaml
kubectl -n spire-system rollout status deploy/spire-server --timeout=300s
scripts/spire-bootstrap.sh bundle

# 2. SPIRE agents on every node, then one registration entry per agent and workload.
kubectl apply -f deploy/spire/spire-agent.yaml
kubectl -n spire-system rollout status ds/spire-agent --timeout=300s
scripts/spire-bootstrap.sh register

# 3. Demo workloads. Namespaces first, because the kubeadm pull secret lives in them.
kubectl apply -f deploy/k8s/base/namespaces.yaml
if [ "$TARGET" = "kubeadm" ]; then
  TOKEN="$(gcloud auth print-access-token)"
  for ns in cart checkout; do
    kubectl -n "$ns" create secret docker-registry artifact-registry \
      --docker-server="$REGION-docker.pkg.dev" \
      --docker-username=oauth2accesstoken \
      --docker-password="$TOKEN" \
      --dry-run=client -o yaml | kubectl apply -f -
  done
  echo "Created pull secret 'artifact-registry'. The access token expires in about an hour;"
  echo "re-run this script to refresh it if pods later fail with ImagePullBackOff."
fi

kubectl kustomize "deploy/k8s/overlays/$TARGET" | sed "s#AR_PREFIX#$AR_PREFIX#g" | kubectl apply -f -
kubectl -n cart rollout status deploy/cart-service --timeout=300s
kubectl -n checkout rollout status deploy/checkout-service --timeout=300s
echo "Deployed. Run scripts/demo.sh to see allowed and rejected calls."
