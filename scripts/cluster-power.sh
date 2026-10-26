#!/usr/bin/env bash
# Puts the demo cluster into dormancy and brings it back, like a cold site.
#
#   scripts/cluster-power.sh status
#   scripts/cluster-power.sh off            Scale the node pool to 0. Node and disk charges stop. The GKE
#                                           control-plane fee can still apply (free tier covers one zonal cluster).
#   scripts/cluster-power.sh off --delete   Delete the cluster. Cluster cost goes to 0. Images stay in
#                                           Artifact Registry (a few cents a month).
#   scripts/cluster-power.sh on             Scale nodes back up, or recreate the cluster if it was deleted,
#                                           then redeploy SPIRE, the registration entries, and both services.
#
# Required: PROJECT_ID, REGION (for deploy.sh). Optional: ZONE (us-central1-a), CLUSTER (spiffe-demo),
#           NODES (2), MACHINE_TYPE (e2-standard-4), NODE_POOL (default-pool).
set -euo pipefail

cd "$(dirname "$0")/.."

ACTION="${1:-status}"
DELETE=0
[ "${2:-}" = "--delete" ] && DELETE=1

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
ZONE="${ZONE:-us-central1-a}"
CLUSTER="${CLUSTER:-spiffe-demo}"
NODES="${NODES:-2}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-standard-4}"
NODE_POOL="${NODE_POOL:-default-pool}"

say() { printf '== %s\n' "$*"; }

cluster_exists() {
  gcloud container clusters describe "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" >/dev/null 2>&1
}

node_count() {
  gcloud container clusters describe "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" \
    --format='value(currentNodeCount)' 2>/dev/null || echo 0
}

get_credentials() {
  gcloud container clusters get-credentials "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" >/dev/null
}

wait_for_nodes() {
  local deadline=$((SECONDS + 900))
  until [ "$(kubectl get nodes --no-headers 2>/dev/null | grep -c ' Ready')" -ge "$NODES" ]; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "timed out waiting for $NODES Ready nodes" >&2; exit 1; }
    sleep 15
  done
}

case "$ACTION" in
  status)
    if cluster_exists; then
      say "Cluster $CLUSTER: exists, nodes=$(node_count)"
      get_credentials
      kubectl get pods -A --no-headers 2>/dev/null | awk '{print $1, $2, $4}' | column -t || true
    else
      say "Cluster $CLUSTER: deleted (dormant at zero cluster cost)"
    fi
    ;;

  off)
    if ! cluster_exists; then
      say "Already dormant: cluster $CLUSTER does not exist"
      exit 0
    fi
    if [ "$DELETE" = 1 ]; then
      say "Deleting cluster $CLUSTER (state in SPIRE and the workloads is recreated by 'on')"
      gcloud container clusters delete "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" --quiet
    else
      say "Scaling $NODE_POOL in $CLUSTER to 0 nodes"
      gcloud container clusters resize "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" \
        --node-pool "$NODE_POOL" --num-nodes 0 --quiet
    fi
    say "Dormant. Run 'scripts/cluster-power.sh on' to bring it back."
    ;;

  on)
    if cluster_exists; then
      if [ "$(node_count)" -lt "$NODES" ]; then
        say "Scaling $NODE_POOL in $CLUSTER to $NODES nodes"
        gcloud container clusters resize "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" \
          --node-pool "$NODE_POOL" --num-nodes "$NODES" --quiet
      fi
    else
      say "Recreating cluster $CLUSTER ($NODES x $MACHINE_TYPE)"
      gcloud container clusters create "$CLUSTER" --zone "$ZONE" --project "$PROJECT_ID" \
        --num-nodes "$NODES" --machine-type "$MACHINE_TYPE" --quiet
    fi
    get_credentials
    say "Waiting for $NODES Ready nodes"
    wait_for_nodes
    # Re-applies SPIRE, republishes the trust bundle, re-registers workloads for the new agents,
    # and deploys both services. Safe to re-run; the images are already in Artifact Registry.
    say "Redeploying SPIRE and the services"
    PROJECT_ID="$PROJECT_ID" REGION="$REGION" scripts/deploy.sh gke
    say "Active. Run scripts/demo.sh to check the flow."
    ;;

  *)
    echo "usage: $0 status | off [--delete] | on" >&2
    exit 2
    ;;
esac
