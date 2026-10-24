#!/usr/bin/env bash
# Runs every check: unit tests for both services, manifest validation, and the live end-to-end demo.
#
#   scripts/test-all.sh              everything, including the live demo (needs kubectl context)
#   RUN_E2E=0 scripts/test-all.sh    offline checks only
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

for svc in cart-service checkout-service; do
  echo "== Unit tests: $svc"
  (cd "services/$svc" && mvn -B -q verify)
done

echo "== Manifest validation"
scripts/validate-manifests.sh

if [ "${RUN_E2E:-1}" = 1 ]; then
  echo "== End-to-end demo"
  scripts/demo.sh
fi

echo "All checks passed."
