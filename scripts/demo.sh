#!/usr/bin/env bash
# Demo: an allowed call succeeds, and calls from an unregistered workload are rejected.
#
#   1. Add items to a cart in the cart namespace and check out. Cart calls checkout with its
#      JWT-SVID. Expect 201 with an order.
#   2. From the "rogue" namespace, which has no SPIFFE registration entry, call checkout
#      directly with no credential. Expect 401.
#   3. Same workload with a forged token claiming to be the cart service (alg=none). Expect 401.
#
# Requires kubectl access to the cluster and deploy.sh to have completed.
set -euo pipefail

ROGUE_NS="rogue"
ROGUE_IMAGE="curlimages/curl:8.11.1"
CHECKOUT_URL="http://checkout-service.checkout.svc.cluster.local/api/v1/orders"
LOCAL_PORT="${LOCAL_PORT:-18080}"

say() { printf '\n== %s\n' "$*"; }

# Base64url without padding, as JWTs require.
b64url() { base64 | tr -d '=\n' | tr '/+' '_-'; }

say "Step 0: show the registration entries that SPIRE holds"
kubectl -n spire-system exec deploy/spire-server -- /opt/spire/bin/spire-server entry show \
  | grep -E 'SPIFFE ID|Selector' | grep -E 'ns/(cart|checkout)|k8s:' || true

say "Step 1: ALLOWED. cart-service adds items and checks out through checkout-service"
kubectl -n cart port-forward svc/cart-service "$LOCAL_PORT:80" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true; kubectl -n "$ROGUE_NS" delete pod rogue-client --ignore-not-found >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 30); do
  curl -sf "http://localhost:$LOCAL_PORT/actuator/health" >/dev/null 2>&1 && break
  sleep 1
done

curl -s -o /dev/null -w 'add item   -> HTTP %{http_code}\n' -X POST "http://localhost:$LOCAL_PORT/api/carts/demo-cart/items" \
  -H 'Content-Type: application/json' -d '{"sku":"book-001","quantity":2,"unitPriceCents":1250}'
ORDER_FILE="$(mktemp)"
curl -s -o "$ORDER_FILE" -w 'checkout    -> HTTP %{http_code}\n' -X POST "http://localhost:$LOCAL_PORT/api/carts/demo-cart/checkout"
cat "$ORDER_FILE"; echo
rm -f "$ORDER_FILE"

say "Step 2: REJECTED. unregistered 'rogue' workload calls checkout with no credential"
kubectl create namespace "$ROGUE_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$ROGUE_NS" run rogue-client --rm -i --restart=Never --quiet --image="$ROGUE_IMAGE" -- \
  sh -c "curl -s -w '\nno credential -> HTTP %{http_code}\n' -X POST '$CHECKOUT_URL' \
    -H 'Content-Type: application/json' -d '{\"cartId\":\"x\",\"items\":[{\"sku\":\"s\",\"quantity\":1,\"unitPriceCents\":1}]}'"

say "Step 3: REJECTED. same workload sends a forged JWT claiming to be cart-service (alg=none)"
HEADER="$(printf '{"alg":"none","typ":"JWT"}' | b64url)"
PAYLOAD="$(printf '{"sub":"spiffe://example.org/ns/cart/sa/cart-service","aud":["checkout-service"],"exp":9999999999}' | b64url)"
FORGED="$HEADER.$PAYLOAD."
kubectl -n "$ROGUE_NS" run rogue-client --rm -i --restart=Never --quiet --image="$ROGUE_IMAGE" -- \
  sh -c "curl -s -w '\nforged token -> HTTP %{http_code}\n' -X POST '$CHECKOUT_URL' \
    -H 'Authorization: Bearer $FORGED' -H 'Content-Type: application/json' \
    -d '{\"cartId\":\"x\",\"items\":[{\"sku\":\"s\",\"quantity\":1,\"unitPriceCents\":1}]}'"

say "Checkout service log (last lines, showing the accept and reject decisions)"
kubectl -n checkout logs deploy/checkout-service --tail=40 | grep -E 'Authenticated caller|Rejected' || true
