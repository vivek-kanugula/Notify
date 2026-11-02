# Notify Verification Suite Script (Windows PowerShell)
$ErrorActionPreference = "Stop"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Notify Verification Suite " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$gatePassed = $true

# 1. Backend Verification
Write-Host "`n[1/3] Running Backend Verification (Maven test)..." -ForegroundColor Yellow
try {
 Push-Location backend
 .\mvnw.cmd test
 if ($LASTEXITCODE -ne 0) { throw "Backend verification failed with exit code $LASTEXITCODE" }
 Pop-Location
 Write-Host " -> Backend verification PASSED" -ForegroundColor Green
} catch {
 Pop-Location
 Write-Host " -> Backend verification FAILED" -ForegroundColor Red
 $gatePassed = $false
}

# 2. Frontend Verification
Write-Host "`n[2/3] Running Frontend Verification (Tests & TypeScript build)..." -ForegroundColor Yellow
try {
 Push-Location frontend
 npm test
 if ($LASTEXITCODE -ne 0) { throw "Frontend tests failed with exit code $LASTEXITCODE" }
 npm run build
 if ($LASTEXITCODE -ne 0) { throw "Frontend build failed with exit code $LASTEXITCODE" }
 Pop-Location
 Write-Host " -> Frontend verification PASSED" -ForegroundColor Green
} catch {
 Pop-Location
 Write-Host " -> Frontend verification FAILED" -ForegroundColor Red
 $gatePassed = $false
}

# 3. Docker Compose Configuration Check
Write-Host "`n[3/3] Checking Docker Compose Configuration..." -ForegroundColor Yellow
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($null -ne $dockerCmd) {
 try {
 docker compose --profile core --profile app config
 if ($LASTEXITCODE -ne 0) { throw "Docker compose validation failed with exit code $LASTEXITCODE" }
 Write-Host " -> Docker Compose validation PASSED" -ForegroundColor Green
 } catch {
 Write-Host " -> Docker Compose validation FAILED" -ForegroundColor Red
 $gatePassed = $false
 }
} else {
 Write-Host " -> Docker CLI not found on PATH. Compose validation NOT RUN (Prerequisite pending: Docker Desktop)." -ForegroundColor DarkYellow
}

Write-Host "`n==========================================" -ForegroundColor Cyan
if ($gatePassed) {
 Write-Host " VERIFICATION RESULT: PASSED" -ForegroundColor Green
 exit 0
} else {
 Write-Host " VERIFICATION RESULT: FAILED" -ForegroundColor Red
 exit 1
}
