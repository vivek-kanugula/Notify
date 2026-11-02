# - advisory load benchmark runner (Windows PowerShell).
#
# Drives scripts/perf/k6-notification-load.js against an already-running backend and writes the raw k6
# summary next to the evidence report. Advisory only (/): this never fails a build and the 200 rps
# target throughput is an architectural performance goal.
#
# Prerequisites (reported honestly, never auto-installed):
# - k6 on PATH (https://k6.io/docs/get-started/installation/)
# - a running Notify backend (local profile) reachable at -BaseUrl
# - an API key with the notifications:write scope
#
# Usage:
# .\scripts\run-load-benchmark.ps1 -ApiKey "<key>" [-BaseUrl http://localhost:8080] [-Vus 200] [-Duration 60s]

param(
 [Parameter(Mandatory = $true)][string]$ApiKey,
 [string]$BaseUrl = "http://localhost:8080",
 [int]$Vus = 200,
 [string]$Duration = "60s"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$script = Join-Path $repoRoot "scripts\perf\k6-notification-load.js"
$evidenceDir = Join-Path $repoRoot "docs\build\evidence"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryPath = Join-Path $evidenceDir "n23a-k6-summary-$stamp.json"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Notify advisory load benchmark " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$k6 = Get-Command k6 -ErrorAction SilentlyContinue
$k6Binary = if ($null -ne $k6) { $k6.Source } elseif (Test-Path (Join-Path $repoRoot "scripts\perf\k6.exe")) { Join-Path $repoRoot "scripts\perf\k6.exe" } else { $null }

if ($null -eq $k6Binary) {
 Write-Host " -> k6 not found on PATH or scripts/perf/k6.exe. Benchmark NOT RUN." -ForegroundColor DarkYellow
 Write-Host " Install k6, then re-run this script. No results were recorded." -ForegroundColor DarkYellow
 exit 0
}

if (-not (Test-Path $evidenceDir)) {
 New-Item -ItemType Directory -Path $evidenceDir | Out-Null
}

Write-Host "`nTarget : $BaseUrl"
Write-Host "VUs : $Vus"
Write-Host "Duration : $Duration"
Write-Host "Summary out : $summaryPath`n"

& $k6Binary run `
 -e "BASE_URL=$BaseUrl" `
 -e "API_KEY=$ApiKey" `
 -e "VUS=$Vus" `
 -e "DURATION=$Duration" `
 --summary-export $summaryPath `
 $script

$k6Exit = $LASTEXITCODE

Write-Host "`n=========================================="
if ($k6Exit -eq 0) {
 Write-Host " BENCHMARK COMPLETED (advisory)" -ForegroundColor Green
 Write-Host " Raw summary: $summaryPath"
 Write-Host " Record the measured rps and p50/p95/p99 in docs/build/evidence/N23A_PERFORMANCE_REPORT.md"
} else {
 Write-Host " BENCHMARK RUN FAILED (exit $k6Exit) - record this honestly as FAILED, do not omit it" -ForegroundColor Red
}

# Advisory by resilience policy/: always exit 0 so no pipeline treats throughput as a gate.
exit 0
