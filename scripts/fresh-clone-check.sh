#!/usr/bin/env bash
# Notify Fresh Clone Verification Script (POSIX Bash)
# Verifies repository setup, prerequisites, compiler toolchains, tests, and compose configurations from a clean clone.

set -eo pipefail

echo "=========================================="
echo " Notify Fresh Clone Verification "
echo "=========================================="

ALL_PASSED=true

# 1. Prerequisite Toolchain Check
echo -e "\n[1/5] Checking Toolchain Prerequisites..."

if command -v javac >/dev/null 2>&1; then
 JAVA_VERSION=$(javac -version 2>&1)
 echo " -> Java compiler: $JAVA_VERSION"
 if echo "$JAVA_VERSION" | grep -q "25"; then
 echo " -> Java 25 detected: PASSED"
 else
 echo " -> Warning: Expected Java 25, found: $JAVA_VERSION"
 fi
else
 echo " -> javac not found on PATH. Please install JDK 25."
 ALL_PASSED=false
fi

if command -v node >/dev/null 2>&1; then
 NODE_VERSION=$(node -v 2>&1)
 echo " -> Node.js version: $NODE_VERSION"
else
 echo " -> node not found on PATH. Please install Node.js 22+."
 ALL_PASSED=false
fi

if command -v docker >/dev/null 2>&1; then
 DOCKER_VERSION=$(docker --version 2>&1)
 echo " -> Docker version: $DOCKER_VERSION"
else
 echo " -> Docker CLI not found. (Docker required for container runtime)"
fi

# 2. Backend Compilation & Toolchain Check
echo -e "\n[2/5] Checking Backend Build (Maven test-compile)..."
if (cd backend && ./mvnw test-compile -B); then
 echo " -> Backend compilation PASSED"
else
 echo " -> Backend compilation FAILED"
 ALL_PASSED=false
fi

# 3. Frontend Unit Tests & Production Build
echo -e "\n[3/5] Checking Frontend Unit Tests & Build..."
if (cd frontend && npm test && npm run build); then
 echo " -> Frontend tests and production build PASSED"
else
 echo " -> Frontend verification FAILED"
 ALL_PASSED=false
fi

# 4. Production Profile Security Check
echo -e "\n[4/5] Running Production Profile Security Verification..."
if (cd backend && ./mvnw test -Dtest=ProductionProfileValidatorTest -B); then
 echo " -> Production profile security guards PASSED"
else
 echo " -> Production profile security guards FAILED"
 ALL_PASSED=false
fi

# 5. Docker Compose Config Validation
echo -e "\n[5/5] Checking Docker Compose Configuration..."
if command -v docker >/dev/null 2>&1; then
 if docker compose -f infra/docker-compose.yml --profile core --profile app config >/dev/null 2>&1; then
 echo " -> Docker Compose validation PASSED"
 else
 echo " -> Docker Compose validation FAILED"
 ALL_PASSED=false
 fi
else
 echo " -> Docker CLI not found. Compose validation SKIPPED."
fi

echo -e "\n=========================================="
if [ "$ALL_PASSED" = true ]; then
 echo " FRESH CLONE VERIFICATION: PASSED"
 exit 0
else
 echo " FRESH CLONE VERIFICATION: FAILED"
 exit 1
fi

