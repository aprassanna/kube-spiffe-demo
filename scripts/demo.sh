#!/usr/bin/env bash
# End-to-end demo and assertions for the mutual-TLS identity flow on a live cluster.
#
#   1. ALLOWED:  cart-service (SPIFFE ID ns/cart/sa/cart-service) checks out through checkout-service
#                over mTLS. Expect 201, and checkout logs the authenticated caller.
#   2. REJECTED: a "rogue" workload with no SPIRE identity connects with no client certificate.
#                Expect the TLS handshake to fail (curl exit != 0, no HTTP response).
#   3. REJECTED: the same workload presents a self-signed certificate that SPIRE did not issue.
#                Expect the handshake to fail again.
#
# Exits non-zero if any expectation fails.
set -uo pipefail

ROGUE_NS="rogue"
ROGUE_IMAGE="alpine:3.20"
CHECKOUT_URL="https://checkout-service.checkout.svc.cluster.local/api/v1/orders"
CALLER_ID="spiffe://example.org/ns/cart/sa/cart-service"
LOCAL_PORT="${LOCAL_PORT:-18080}"
PASSED=0
FAILED=0

say() { printf '\n== %s\n' "$*"; }

expect() {
  local label="$1" ok="$2" detail="$3"
  if [ "$ok" = yes ]; then
    PASSED=$((PASSED + 1)); printf 'PASS  %s\n' "$label"
  else
    FAILED=$((FAILED + 1)); printf 'FAIL  %s\n      %s\n' "$label" "$detail"
  fi
}

# Runs a shell snippet in a throwaway pod in the rogue namespace (no SPIRE registration, no socket).
rogue() {
  kubectl -n "$ROGUE_NS" run "rogue-$RANDOM" --rm -i --restart=Never --quiet --image="$ROGUE_IMAGE" -- \
    sh -c "apk add --no-cache -q curl openssl >/dev/null 2>&1 && $1" 2>&1
}

say "SPIRE registration entries"
kubectl -n spire-system exec deploy/spire-server -- /opt/spire/bin/spire-server entry show 2>&1 \
  | grep -E 'SPIFFE ID|Selector' | grep -E 'ns/(cart|checkout)|k8s:(ns|sa):(cart|checkout)' || true

say "1. ALLOWED: cart -> checkout over mTLS"
kubectl -n cart port-forward svc/cart-service "$LOCAL_PORT:80" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -sf "http://localhost:$LOCAL_PORT/actuator/health" >/dev/null 2>&1 && break
  sleep 1
done

ADD_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$LOCAL_PORT/api/carts/demo-cart/items" \
  -H 'Content-Type: application/json' -d '{"sku":"book-001","quantity":2,"unitPriceCents":1250}')"
expect "add item to cart returns 201" "$([ "$ADD_CODE" = 201 ] && echo yes || echo no)" "got HTTP $ADD_CODE"

ORDER_FILE="$(mktemp)"
CHECKOUT_CODE="$(curl -s -o "$ORDER_FILE" -w '%{http_code}' -X POST "http://localhost:$LOCAL_PORT/api/carts/demo-cart/checkout")"
cat "$ORDER_FILE"; echo
expect "checkout returns 201" "$([ "$CHECKOUT_CODE" = 201 ] && echo yes || echo no)" "got HTTP $CHECKOUT_CODE"
expect "order records the mTLS caller SPIFFE ID" \
  "$(grep -q "\"placedBy\":\"$CALLER_ID\"" "$ORDER_FILE" && echo yes || echo no)" "placedBy missing or different"
rm -f "$ORDER_FILE"

kill "$PF_PID" 2>/dev/null || true
trap - EXIT

LOG_LINE="$(kubectl -n checkout logs deploy/checkout-service --tail=200 | grep "Authenticated caller $CALLER_ID" | tail -1)"
expect "checkout log shows the authenticated caller" "$([ -n "$LOG_LINE" ] && echo yes || echo no)" "no matching log line"
[ -n "$LOG_LINE" ] && echo "log: $LOG_LINE"

kubectl create namespace "$ROGUE_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

say "2. REJECTED: rogue workload, no client certificate"
OUT="$(rogue "curl -sk -o /dev/null -w 'http=%{http_code}' -X POST $CHECKOUT_URL -H 'Content-Type: application/json' -d {}; echo \" curl_exit=\$?\"")"
echo "$OUT"
expect "no-certificate call fails at the TLS handshake" \
  "$(echo "$OUT" | grep -q 'http=000' && echo "$OUT" | grep -Eq 'curl_exit=[1-9]' && echo yes || echo no)" "$OUT"

say "3. REJECTED: rogue workload, self-signed certificate not issued by SPIRE"
OUT="$(rogue "openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout /tmp/k.pem -out /tmp/c.pem -days 1 -subj /CN=rogue -addext subjectAltName=URI:spiffe://example.org/ns/cart/sa/cart-service >/dev/null 2>&1 && curl -sk --cert /tmp/c.pem --key /tmp/k.pem -o /dev/null -w 'http=%{http_code}' -X POST $CHECKOUT_URL -H 'Content-Type: application/json' -d {}; echo \" curl_exit=\$?\"")"
echo "$OUT"
expect "self-signed certificate fails at the TLS handshake" \
  "$(echo "$OUT" | grep -q 'http=000' && echo "$OUT" | grep -Eq 'curl_exit=[1-9]' && echo yes || echo no)" "$OUT"

say "Result: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
