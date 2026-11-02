#!/usr/bin/env bash
set -eo pipefail

BASE_URL="${1:-http://localhost:8080}"
echo "Running smoke tests against $BASE_URL ..."

STATUS=$(curl -s "$BASE_URL/actuator/health/liveness" | grep -o '"status":"UP"' || true)
if [ -n "$STATUS" ]; then
 echo " [OK] /actuator/health/liveness is UP"
else
 echo " [FAIL] /actuator/health/liveness did not return UP"
 exit 1
fi

echo "Smoke tests PASSED."
exit 0
