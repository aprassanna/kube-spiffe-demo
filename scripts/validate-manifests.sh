#!/usr/bin/env bash
# Renders every manifest and validates it against the Kubernetes 1.34 schemas.
# CRD-backed kinds without a published schema are skipped with -ignore-missing-schemas.
set -euo pipefail

cd "$(dirname "$0")/.."

KUBECONFORM=(kubeconform -strict -summary -ignore-missing-schemas -kubernetes-version 1.34.0)
PLACEHOLDER_PREFIX="us-central1-docker.pkg.dev/example-project/spiffe-demo"

echo "== SPIRE"
"${KUBECONFORM[@]}" deploy/spire/spire-server.yaml deploy/spire/spire-agent.yaml

for target in gke kubeadm; do
  echo "== Workloads ($target overlay)"
  kubectl kustomize "deploy/k8s/overlays/$target" \
    | sed "s#AR_PREFIX#$PLACEHOLDER_PREFIX#g" \
    | "${KUBECONFORM[@]}" -
done
