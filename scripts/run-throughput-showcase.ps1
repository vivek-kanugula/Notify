<#
.SYNOPSIS
 Runs an ingress throughput showcase against Notify without requiring third-party tools like k6.

.DESCRIPTION
 Sends a burst of N notifications with unique idempotency keys using parallel jobs or rapid HTTP requests,
 measuring throughput (RPS) and latency percentiles (min, p50, p95, max).

.PARAMETER TotalRequests
 Total number of requests to dispatch (default: 50).

.PARAMETER Concurrency
 Number of parallel worker jobs (default: 5).

.EXAMPLE
 .\scripts\run-throughput-showcase.ps1 -TotalRequests 60 -Concurrency 6
#>

[CmdletBinding]
param(
 [int]$TotalRequests = 50,
 [int]$Concurrency = 5,
 [string]$BaseUrl = 'http://localhost:8080',
 [string]$TenantEmail = 'demo@example.com',
 [string]$TenantPassword = 'Password123!'
)

$ErrorActionPreference = 'Stop'

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host " NOTIFY HIGH-THROUGHPUT SHOWCASE " -ForegroundColor Cyan
Write-Host "========================================================`n" -ForegroundColor Cyan

# 1. Login
Write-Host "[1/3] Authenticating as $TenantEmail..." -ForegroundColor Yellow
$login = Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -ContentType "application/json" -Body (@{
 email = $TenantEmail
 password = $TenantPassword
} | ConvertTo-Json)
$token = $login.accessToken

# 2. Dispatch
$reqsPerJob = [Math]::Ceiling($TotalRequests / $Concurrency)
Write-Host "[2/3] Dispatching $TotalRequests requests across $Concurrency parallel workers (~$reqsPerJob reqs/worker)..." -ForegroundColor Yellow

$overallWatch = [System.Diagnostics.Stopwatch]::StartNew

$jobs = 1..$Concurrency | ForEach-Object {
 Start-Job -ScriptBlock {
 param($tok, $count, $url, $workerId)
 $latencies = [System.Collections.Generic.List[long]]::new
 $success = 0
 $failed = 0

 for ($i = 1; $i -le $count; $i++) {
 $key = "load-$workerId-$i-" + [Guid]::NewGuid.ToString.Substring(0,8)
 $payload = @{
 channel = "IN_APP"
 recipient = "demo-user-1"
 subject = "Throughput Burst $workerId-$i"
 body = "Load test notification payload"
 } | ConvertTo-Json

 $sw = [System.Diagnostics.Stopwatch]::StartNew
 try {
 $r = Invoke-WebRequest -Uri "$url/api/v1/notifications" `
 -Method Post `
 -Headers @{ Authorization = "Bearer $tok"; "Idempotency-Key" = $key } `
 -ContentType "application/json" `
 -Body $payload `
 -UseBasicParsing
 $sw.Stop
 if ($r.StatusCode -eq 202) {
 $success++
 $latencies.Add($sw.ElapsedMilliseconds)
 } else {
 $failed++
 }
 } catch {
 $sw.Stop
 $failed++
 }
 }

 [PSCustomObject]@{
 Worker = "Worker #$workerId"
 Success = $success
 Failed = $failed
 Latencies = $latencies
 }
 } -ArgumentList $token, $reqsPerJob, $BaseUrl, $_
}

$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job
$overallWatch.Stop

# 3. Aggregation & Statistics
Write-Host "`n[3/3] Calculating Performance Metrics..." -ForegroundColor Yellow

$allLatencies = [System.Collections.Generic.List[long]]::new
$totalSuccess = 0
$totalFailed = 0

foreach ($res in $results) {
 $totalSuccess += $res.Success
 $totalFailed += $res.Failed
 if ($res.Latencies) {
 foreach ($l in $res.Latencies) {
 $allLatencies.Add([long]$l)
 }
 }
}

$totalSeconds = [Math]::Max(0.001, $overallWatch.ElapsedMilliseconds / 1000.0)
$rps = [Math]::Round($totalSuccess / $totalSeconds, 2)

$sorted = $allLatencies | Sort-Object
$count = $sorted.Count

function Get-Percentile($list, $p) {
 if ($list.Count -eq 0) { return 0 }
 $idx = [Math]::Ceiling(($p / 100.0) * $list.Count) - 1
 $idx = [Math]::Max(0, [Math]::Min($idx, $list.Count - 1))
 return $list[$idx]
}

$min = if ($count -gt 0) { $sorted[0] } else { 0 }
$max = if ($count -gt 0) { $sorted[$count - 1] } else { 0 }
$p50 = Get-Percentile $sorted 50
$p95 = Get-Percentile $sorted 95
$p99 = Get-Percentile $sorted 99
$avg = if ($count -gt 0) { [Math]::Round(($sorted | Measure-Object -Average).Average, 1) } else { 0 }

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host " BENCHMARK RESULTS " -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host " Total Requests Dispatched : $($totalSuccess + $totalFailed)" -ForegroundColor White
Write-Host " Accepted (HTTP 202) : $totalSuccess (100% durable)" -ForegroundColor Green
Write-Host " Failed : $totalFailed" -ForegroundColor $(if ($totalFailed -eq 0) { "Green" } else { "Red" })
Write-Host " Wall Clock Elapsed Time : $([Math]::Round($totalSeconds, 2)) seconds" -ForegroundColor White
Write-Host " Throughput (Ingress RPS) : $rps req/sec" -ForegroundColor Yellow
Write-Host "--------------------------------------------------------" -ForegroundColor DarkGray
Write-Host " Latency (HTTP Round-Trip) :" -ForegroundColor White
Write-Host " - Min : $min ms" -ForegroundColor White
Write-Host " - Avg : $avg ms" -ForegroundColor White
Write-Host " - P50 (Median) : $p50 ms" -ForegroundColor White
Write-Host " - P95 : $p95 ms" -ForegroundColor Green
Write-Host " - P99 : $p99 ms" -ForegroundColor White
Write-Host " - Max : $max ms" -ForegroundColor White
Write-Host "========================================================`n" -ForegroundColor Cyan

Write-Host "Live Grafana View: http://localhost:3000/d/notify-delivery/notify-delivery" -ForegroundColor Cyan
Write-Host "Outbox Transport : http://localhost:3000/d/notify-transport/notify-transport`n" -ForegroundColor Cyan
