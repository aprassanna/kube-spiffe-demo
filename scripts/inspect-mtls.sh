#!/usr/bin/env bash
# Shows where the demo runs, which SPIRE identities and tokens exist, and performs a live mTLS
# handshake with an X.509-SVID issued to a pod running as cart-service.
#
# Required: PROJECT_ID. Optional: ZONE (default us-central1-a), CLUSTER (default spiffe-demo).
set -uo pipefail

cd "$(dirname "$0")/.."

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
ZONE="${ZONE:-us-central1-a}"
CLUSTER="${CLUSTER:-spiffe-demo}"
CHECKOUT="checkout-service.checkout.svc.cluster.local:443"
CONSOLE="https://console.cloud.google.com"

say() { printf '\n== %s\n' "$*"; }

say "Where this runs (Google Cloud console)"
echo "Project:             $PROJECT_ID"
echo "GKE cluster:         $CONSOLE/kubernetes/clusters/details/$ZONE/$CLUSTER/details?project=$PROJECT_ID"
echo "Workloads:           $CONSOLE/kubernetes/workload/overview?project=$PROJECT_ID"
echo "Nodes (Compute):     $CONSOLE/compute/instances?project=$PROJECT_ID"
echo "Artifact Registry:   $CONSOLE/artifacts/docker/$PROJECT_ID/us-central1/spiffe-demo?project=$PROJECT_ID"
echo "Cloud Build history: $CONSOLE/cloud-build/builds?project=$PROJECT_ID"
echo "Logs (checkout):     $CONSOLE/logs/query;query=resource.labels.container_name%3D%22checkout-service%22?project=$PROJECT_ID"

say "SPIRE server: attested agents (one per node, proving node identity)"
kubectl -n spire-system exec deploy/spire-server -- /opt/spire/bin/spire-server agent list < /dev/null 2>&1 \
  | grep -E 'SPIFFE ID|Attestation type|Expiration' || true

say "SPIRE server: registration entries (ServiceAccount to SPIFFE ID)"
kubectl -n spire-system exec deploy/spire-server -- /opt/spire/bin/spire-server entry show < /dev/null 2>&1 \
  | grep -E 'SPIFFE ID|Parent ID|Selector|TTL' || true

say "Tokens in play"
echo "Agent node attestation: projected ServiceAccount token, audience spire-server, 2h, rotated by kubelet:"
kubectl -n spire-system get ds spire-agent -o jsonpath='{.spec.template.spec.volumes[?(@.name=="spire-token")].projected.sources[0].serviceAccountToken}'
echo
echo "Workload pods: automountServiceAccountToken (false means no Kubernetes token inside the app):"
kubectl -n cart get deploy cart-service -o jsonpath='{.spec.template.spec.automountServiceAccountToken}'; echo
kubectl -n checkout get deploy checkout-service -o jsonpath='{.spec.template.spec.automountServiceAccountToken}'; echo
echo "Application identity: X.509-SVID (certificate and key) from the Workload API. No bearer token."

say "Inspector pod running as cart-service"
kubectl delete pod svid-inspector -n cart --ignore-not-found >/dev/null 2>&1
trap 'kubectl -n cart delete pod svid-inspector --ignore-not-found >/dev/null 2>&1' EXIT
kubectl apply -f deploy/debug/svid-inspector.yaml >/dev/null
kubectl -n cart wait --for=condition=Ready pod/svid-inspector --timeout=300s >/dev/null 2>&1 || {
  echo "inspector pod did not become ready:"; kubectl -n cart describe pod svid-inspector | tail -15; exit 1; }
kubectl -n cart logs svid-inspector -c fetch-svid 2>&1 | grep -vE '^\s*$' | head -10

INSPECT='
apk add -q --no-cache openssl curl >/dev/null 2>&1
echo "--- X.509-SVID issued to this pod (leaf certificate)"
openssl x509 -in /shared/svid.0.pem -noout -subject -issuer -dates -ext subjectAltName
echo "--- trust bundle (SPIRE CA)"
openssl x509 -in /shared/bundle.0.pem -noout -subject -dates
'
say "The SVID: subject, issuer, validity, SPIFFE ID in the URI SAN"
kubectl -n cart exec -i svid-inspector -c tools -- sh -s <<<"$INSPECT" 2>&1

HANDSHAKE_CHECKOUT='
echo "--- handshake WITH the SVID (checkout requires a client certificate)"
echo | openssl s_client -connect '"$CHECKOUT"' -cert /shared/svid.0.pem -key /shared/svid.0.key \
  -CAfile /shared/bundle.0.pem -brief 2>&1 | grep -E "Protocol|Ciphersuite|Peer certificate|Verification|Server Temp|Peer signature|Client Certificate|Requested|Acceptable" || true
echo "--- application request WITH the SVID (expect 400: passed mTLS and the allow-list, failed body validation)"
printf "POST /api/v1/orders HTTP/1.1\r\nHost: checkout-service\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}" \
  | openssl s_client -quiet -connect '"$CHECKOUT"' -cert /shared/svid.0.pem -key /shared/svid.0.key -CAfile /shared/bundle.0.pem 2>/dev/null | head -1
echo "--- handshake WITHOUT a client certificate (expect failure)"
echo | openssl s_client -connect '"$CHECKOUT"' -CAfile /shared/bundle.0.pem -brief 2>&1 | grep -iE "alert|error|handshake failure|certificate required" | head -2
'
say "Live mTLS handshake against checkout-service"
kubectl -n cart exec -i svid-inspector -c tools -- sh -s <<<"$HANDSHAKE_CHECKOUT" 2>&1

say "Done. The inspector pod is deleted on exit."
