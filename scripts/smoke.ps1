# Notify Smoke Test Suite (PowerShell)
param(
 [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

Write-Host "Running smoke tests against $BaseUrl ..." -ForegroundColor Cyan

try {
 $res = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/liveness" -Method Get
 if ($res.status -eq "UP") {
 Write-Host " [OK] /actuator/health/liveness is UP" -ForegroundColor Green
 } else {
 Write-Host " [FAIL] /actuator/health/liveness status is $($res.status)" -ForegroundColor Red
 exit 1
 }
} catch {
 Write-Host " [FAIL] Failed to connect to $BaseUrl/actuator/health/liveness: $_" -ForegroundColor Red
 exit 1
}

Write-Host "Smoke tests PASSED." -ForegroundColor Green
exit 0
