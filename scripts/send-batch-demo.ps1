<#
.SYNOPSIS
 Dispatches a batch of notifications across all channels (EMAIL, WEBHOOK, IN_APP) simultaneously.

.DESCRIPTION
 Authenticates as tenant owner, then sends notifications across EMAIL, WEBHOOK, and IN_APP channels
 in round-robin or burst mode. Monitors delivery and prints a clean summary table with links.

.PARAMETER CountPerChannel
 Number of notifications to send per channel (default: 3, total = 3 * 3 = 9).

.PARAMETER EmailRecipient
 Email recipient address (default: vivekkanugula@gmail.com).

.PARAMETER InAppRecipient
 In-App user ID (default: demo-user-1).

.PARAMETER WebhookUrl
 Webhook destination URL (default: http://localhost:8080/demo/webhook-receiver).

.EXAMPLE
 .\scripts\send-batch-demo.ps1 -CountPerChannel 5
#>

[CmdletBinding]
param(
 [int]$CountPerChannel = 3,
 [string]$EmailRecipient = 'vivekkanugula@gmail.com',
 [string]$InAppRecipient = 'demo-user-1',
 [string]$WebhookUrl = 'http://localhost:8080/demo/webhook-receiver',
 [string]$BaseUrl = 'http://localhost:8080',
 [string]$TenantEmail = 'demo@example.com',
 [string]$TenantPassword = 'Password123!'
)

$ErrorActionPreference = 'Stop'

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host " NOTIFY MULTI-CHANNEL BATCH DISPATCHER " -ForegroundColor Cyan
Write-Host "========================================================`n" -ForegroundColor Cyan

# 1. Authenticate
Write-Host "[1/3] Authenticating as tenant $TenantEmail..." -ForegroundColor Yellow
$loginRes = Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -ContentType "application/json" -Body (@{
 email = $TenantEmail
 password = $TenantPassword
} | ConvertTo-Json)

$token = $loginRes.accessToken
Write-Host " Authenticated successfully! (Tenant ID: $($loginRes.user.tenantId))`n" -ForegroundColor Green

# 2. Dispatch batch across all channels
Write-Host "[2/3] Dispatching batch ($CountPerChannel per channel, total $($CountPerChannel * 3))..." -ForegroundColor Yellow

$channels = @("IN_APP", "WEBHOOK", "EMAIL")
$dispatched = [System.Collections.Generic.List[PSObject]]::new
$timestamp = [DateTime]::UtcNow.ToString("HH:mm:ss")

for ($i = 1; $i -le $CountPerChannel; $i++) {
 foreach ($ch in $channels) {
 $bodyPayload = switch ($ch) {
 "EMAIL" {
 @{
 channel = "EMAIL"
 recipient = $EmailRecipient
 subject = "Batch Multi-Channel Email #$i [$timestamp]"
 body = "Hello from Notify Multi-Channel Batch! This is email #$i delivered via Resend."
 }
 }
 "WEBHOOK" {
 @{
 channel = "WEBHOOK"
 recipient = $WebhookUrl
 subject = "Batch Webhook Event #$i [$timestamp]"
 body = (@{
 event = "batch_notification"
 batch_index = $i
 timestamp = $timestamp
 status = "dispatched"
 } | ConvertTo-Json -Compress)
 }
 }
 "IN_APP" {
 @{
 channel = "IN_APP"
 recipient = $InAppRecipient
 subject = "Batch In-App Alert #$i [$timestamp]"
 body = "Real-time in-app notification #$i delivered over SSE stream."
 }
 }
 }

 try {
 $resp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/notifications" `
 -Method Post `
 -Headers @{ Authorization = "Bearer $token" } `
 -ContentType "application/json" `
 -Body ($bodyPayload | ConvertTo-Json)

 $dispatched.Add([PSCustomObject]@{
 BatchIndex = $i
 Channel = $ch
 Recipient = $bodyPayload.recipient
 NotificationId = $resp.id
 InitialStatus = $resp.status
 FinalStatus = "PENDING"
 })
 Write-Host " -> [$ch] #$i accepted (ID: $($resp.id))" -ForegroundColor Gray
 } catch {
 Write-Host " -> [$ch] #$i failed: $_" -ForegroundColor Red
 }
 }
}

Write-Host "`nAll $($dispatched.Count) notifications accepted by backend!`n" -ForegroundColor Green

# 3. Monitor delivery progression
Write-Host "[3/3] Checking delivery results..." -ForegroundColor Yellow
Start-Sleep -Seconds 4

foreach ($item in $dispatched) {
 try {
 $detail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/notifications/$($item.NotificationId)" `
 -Headers @{ Authorization = "Bearer $token" }
 $item.FinalStatus = $detail.status
 } catch {
 $item.FinalStatus = "UNKNOWN"
 }
}

Write-Host "`n=== BATCH DISPATCH SUMMARY ===" -ForegroundColor Cyan
$dispatched | Format-Table -Property BatchIndex, Channel, Recipient, InitialStatus, FinalStatus, NotificationId

Write-Host "`n--- Visual Verification Links ---" -ForegroundColor Yellow
Write-Host "1. Frontend Notifications List: http://localhost:5173/app/notifications" -ForegroundColor White
Write-Host "2. Frontend In-App Inbox: http://localhost:5173/app/inbox" -ForegroundColor White
Write-Host "3. Grafana Delivery Dashboard: http://localhost:3000/d/notify-delivery/notify-delivery" -ForegroundColor White
Write-Host "4. Grafana Overview Dashboard: http://localhost:3000/d/notify-overview/notify-overview`n" -ForegroundColor White

