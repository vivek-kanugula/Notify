# Notify Fresh Clone Verification Script (Windows PowerShell)
# Verifies repository setup, prerequisites, compiler toolchains, tests, and compose configurations from a clean clone.

$ErrorActionPreference = "Stop"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Notify Fresh Clone Verification " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$allPassed = $true

# 1. Prerequisite Toolchain Check
Write-Host "`n[1/5] Checking Toolchain Prerequisites..." -ForegroundColor Yellow

# Java 25 Check
$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if ($null -ne $javacCmd) {
 $javaVersion = javac -version 2>&1
 Write-Host " -> Java compiler: $javaVersion" -ForegroundColor Gray
 if ($javaVersion -match "25") {
 Write-Host " -> Java 25 detected: PASSED" -ForegroundColor Green
 } else {
 Write-Host " -> Warning: Expected Java 25, found: $javaVersion" -ForegroundColor DarkYellow
 }
} else {
 Write-Host " -> javac not found on PATH. Please install JDK 25." -ForegroundColor Red
 $allPassed = $false
}

# Node Check
$nodeCmd = Get-Command node -ErrorAction SilentlyContinue
if ($null -ne $nodeCmd) {
 $nodeVersion = node -v 2>&1
 Write-Host " -> Node.js version: $nodeVersion" -ForegroundColor Green
} else {
 Write-Host " -> node not found on PATH. Please install Node.js 22+." -ForegroundColor Red
 $allPassed = $false
}

# Docker Check
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($null -ne $dockerCmd) {
 $dockerVersion = docker --version 2>&1
 Write-Host " -> Docker version: $dockerVersion" -ForegroundColor Green
} else {
 Write-Host " -> Docker CLI not found. (Docker Desktop required for container runtime)" -ForegroundColor DarkYellow
}

# 2. Backend Compilation & Toolchain Check
Write-Host "`n[2/5] Checking Backend Build (Maven test-compile)..." -ForegroundColor Yellow
try {
 Push-Location backend
 .\mvnw.cmd test-compile -B
 if ($LASTEXITCODE -ne 0) { throw "Backend test-compile failed with exit code $LASTEXITCODE" }
 Pop-Location
 Write-Host " -> Backend compilation PASSED" -ForegroundColor Green
} catch {
 Pop-Location
 Write-Host " -> Backend compilation FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# 3. Frontend Unit Tests & Production Build
Write-Host "`n[3/5] Checking Frontend Unit Tests & Build..." -ForegroundColor Yellow
try {
 Push-Location frontend
 npm test
 if ($LASTEXITCODE -ne 0) { throw "Frontend tests failed with exit code $LASTEXITCODE" }
 npm run build
 if ($LASTEXITCODE -ne 0) { throw "Frontend production build failed with exit code $LASTEXITCODE" }
 Pop-Location
 Write-Host " -> Frontend tests and production build PASSED" -ForegroundColor Green
} catch {
 Pop-Location
 Write-Host " -> Frontend verification FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# 4. Production Profile Security Check
Write-Host "`n[4/5] Running Production Profile Security Verification..." -ForegroundColor Yellow
try {
 Push-Location backend
 .\mvnw.cmd test -Dtest=ProductionProfileValidatorTest -B
 if ($LASTEXITCODE -ne 0) { throw "ProductionProfileValidatorTest failed with exit code $LASTEXITCODE" }
 Pop-Location
 Write-Host " -> Production profile security guards PASSED" -ForegroundColor Green
} catch {
 Pop-Location
 Write-Host " -> Production profile security guards FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# 5. Docker Compose Config Validation
Write-Host "`n[5/5] Checking Docker Compose Configuration..." -ForegroundColor Yellow
if ($null -ne $dockerCmd) {
 try {
 docker compose -f infra/docker-compose.yml --profile core --profile app config > $null
 if ($LASTEXITCODE -ne 0) { throw "Docker compose validation failed with exit code $LASTEXITCODE" }
 Write-Host " -> Docker Compose validation PASSED" -ForegroundColor Green
 } catch {
 Write-Host " -> Docker Compose validation FAILED: $_" -ForegroundColor Red
 $allPassed = $false
 }
} else {
 Write-Host " -> Docker CLI not found. Compose validation SKIPPED." -ForegroundColor DarkYellow
}

Write-Host "`n==========================================" -ForegroundColor Cyan
if ($allPassed) {
 Write-Host " FRESH CLONE VERIFICATION: PASSED" -ForegroundColor Green
 exit 0
} else {
 Write-Host " FRESH CLONE VERIFICATION: FAILED" -ForegroundColor Red
 exit 1
}

