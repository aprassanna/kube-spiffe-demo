#!/usr/bin/env bash
# SPIRE bootstrap for the demo. Run after the SPIRE server is up.
#
#   bundle    Publish the SPIRE trust bundle as ConfigMap spire-system/spire-bundle.
#             SPIRE agents read it at startup to trust the server.
#   register  Create one registration entry per (agent, workload) pair. Each entry maps a
#             Kubernetes ServiceAccount (k8s:ns + k8s:sa selectors) to a SPIFFE ID.
#             Re-running is safe: existing entries are skipped.
#   all       bundle, then register.
#
# Re-run "register" after adding nodes so the new agents get entries.
set -euo pipefail

TRUST_DOMAIN="${TRUST_DOMAIN:-example.org}"
SPIRE_NS="spire-system"
SPIRE_SERVER_BIN="/opt/spire/bin/spire-server"

spire() {
  kubectl -n "$SPIRE_NS" exec deploy/spire-server -- "$SPIRE_SERVER_BIN" "$@" < /dev/null
}

publish_bundle() {
  local bundle
  bundle="$(mktemp)"
  trap 'rm -f "$bundle"' RETURN
  spire bundle show -format pem > "$bundle"
  kubectl -n "$SPIRE_NS" create configmap spire-bundle --from-file=bundle.crt="$bundle" \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "Published trust bundle to $SPIRE_NS/spire-bundle"
}

create_entry() {
  local parent_id="$1" spiffe_id="$2" namespace="$3" service_account="$4"
  if spire entry show -parentID "$parent_id" -spiffeID "$spiffe_id" \
      -selector "k8s:ns:$namespace" -selector "k8s:sa:$service_account" | grep -q 'Entry ID'; then
    echo "exists: $spiffe_id (parent ${parent_id##*/})"
    return
  fi
  spire entry create -parentID "$parent_id" -spiffeID "$spiffe_id" \
    -selector "k8s:ns:$namespace" -selector "k8s:sa:$service_account" > /dev/null
  echo "created: $spiffe_id (parent ${parent_id##*/})"
}

register_workloads() {
  # Agents attest after the DaemonSet starts, so on a fresh node pool they may not be listed yet.
  local agents="" deadline=$((SECONDS + 300))
  while [ -z "$agents" ]; do
    agents="$(spire agent list | awk '/SPIFFE ID/ {print $NF}')"
    if [ -z "$agents" ]; then
      [ "$SECONDS" -lt "$deadline" ] || { echo "No attested SPIRE agents after 5 minutes. Check the spire-agent pods." >&2; exit 1; }
      sleep 10
    fi
  done

  while read -r agent_id; do
    create_entry "$agent_id" "spiffe://$TRUST_DOMAIN/ns/cart/sa/cart-service" cart cart-service
    create_entry "$agent_id" "spiffe://$TRUST_DOMAIN/ns/checkout/sa/checkout-service" checkout checkout-service
  done <<< "$agents"
}

case "${1:-all}" in
  bundle) publish_bundle ;;
  register) register_workloads ;;
  all) publish_bundle && register_workloads ;;
  *) echo "usage: $0 [bundle|register|all]" >&2; exit 2 ;;
esac
