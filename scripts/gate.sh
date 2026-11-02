#!/usr/bin/env bash
set -eo pipefail

echo "=========================================="
echo " Notify Verification Suite "
echo "=========================================="

GATE_PASSED=true

# 1. Backend Verification
echo -e "\n[1/3] Running Backend Verification (Maven test)..."
if (cd backend && ./mvnw test); then
 echo " -> Backend verification PASSED"
else
 echo " -> Backend verification FAILED"
 GATE_PASSED=false
fi

# 2. Frontend Verification
# run the unit suite as well as the build, so this matches scripts/gate.ps1. It previously
# ran only `npm run build`, which let a frontend test regression pass the POSIX gate.
echo -e "\n[2/3] Running Frontend Verification (npm test + build)..."
if (cd frontend && npm test && npm run build); then
 echo " -> Frontend verification PASSED"
else
 echo " -> Frontend verification FAILED"
 GATE_PASSED=false
fi

# 3. Docker Compose Configuration Check
echo -e "\n[3/3] Checking Docker Compose Configuration..."
if command -v docker >/dev/null 2>&1; then
 # the compose file lives in infra/, so the bare `docker compose config` this used to run
 # found no file and reported a failure that had nothing to do with the configuration.
 if docker compose -f infra/docker-compose.yml --profile core --profile app config >/dev/null 2>&1; then
 echo " -> Docker Compose validation PASSED"
 else
 echo " -> Docker Compose validation FAILED"
 GATE_PASSED=false
 fi
else
 echo " -> Docker CLI not found. Compose validation NOT RUN."
fi

echo -e "\n=========================================="
if [ "$GATE_PASSED" = true ]; then
 echo " VERIFICATION RESULT: PASSED"
 exit 0
else
 echo " VERIFICATION RESULT: FAILED"
 exit 1
fi
