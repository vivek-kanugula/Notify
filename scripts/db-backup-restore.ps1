# ==========================================================================
# Notify Database Backup & Restore Verification (PowerShell)
# ==========================================================================
# Exercises: pg_dump → integrity check → pg_restore to disposable DB →
# row-count comparison → Flyway schema version parity.
#
# Prerequisites:
# - Docker Desktop running with notify-postgres container healthy
# - PostgreSQL client tools (pg_dump, pg_restore, psql) on PATH,
# OR the script uses docker exec against the running container.
#
# Usage:
# .\scripts\db-backup-restore.ps1 # defaults
# .\scripts\db-backup-restore.ps1 -ContainerName my-pg # custom container
# .\scripts\db-backup-restore.ps1 -BackupDir C:\backups # custom output dir
# ==========================================================================
param(
 [string]$ContainerName = "notify-postgres",
 [string]$DbName = "notify",
 [string]$DbUser = "notify_user",
 [string]$VerifyDb = "notify_verify",
 [string]$BackupDir = ".\backups"
)

$ErrorActionPreference = "Stop"

if ($VerifyDb.Trim.ToLower -eq $DbName.Trim.ToLower) {
 Write-Host "FATAL: Verification database name ('$VerifyDb') cannot match target database name ('$DbName'). Aborting to prevent data loss." -ForegroundColor Red
 exit 1
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupFile = "notify_backup_${timestamp}.dump"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Notify Database Backup & Restore Proof " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

$allPassed = $true

# ── Helper: run command in postgres container ───────────────────────────
function Invoke-PgCmd {
 param([string]$Cmd)
 docker exec $ContainerName sh -c $Cmd
 if ($LASTEXITCODE -ne 0) { throw "Command failed: $Cmd" }
}

# ── Helper: run psql query, return output ───────────────────────────────
function Invoke-Psql {
 param([string]$Query, [string]$Database = $DbName)
 $result = docker exec $ContainerName psql -U $DbUser -d $Database -t -A -c $Query
 if ($LASTEXITCODE -ne 0) { throw "psql query failed: $Query" }
 return $result
}

# ── 1. Create backup directory ──────────────────────────────────────────
Write-Host "[1/6] Creating backup directory..." -ForegroundColor Yellow
if (-not (Test-Path $BackupDir)) {
 New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}
Write-Host " -> Directory: $BackupDir" -ForegroundColor Green

# ── 2. Database dump ────────────────────────────────────────────────────
Write-Host "`n[2/6] Running pg_dump (custom format)..." -ForegroundColor Yellow
try {
 Invoke-PgCmd "pg_dump -U $DbUser -Fc $DbName > /tmp/$backupFile"
 docker cp "${ContainerName}:/tmp/${backupFile}" "${BackupDir}\${backupFile}"
 $fileInfo = Get-Item "${BackupDir}\${backupFile}"
 Write-Host " -> Backup created: $($fileInfo.Name) ($($fileInfo.Length) bytes)" -ForegroundColor Green
 if ($fileInfo.Length -lt 1024) {
 Write-Host " -> WARNING: Backup file suspiciously small ($($fileInfo.Length) bytes)" -ForegroundColor Red
 $allPassed = $false
 }
} catch {
 Write-Host " -> pg_dump FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# ── 3. Integrity check ─────────────────────────────────────────────────
Write-Host "`n[3/6] Verifying dump integrity (pg_restore --list)..." -ForegroundColor Yellow
try {
 docker cp "${BackupDir}\${backupFile}" "${ContainerName}:/tmp/${backupFile}"
 $tocOutput = docker exec $ContainerName pg_restore --list "/tmp/$backupFile" 2>&1
 if ($LASTEXITCODE -ne 0) { throw "pg_restore --list failed" }
 $tocLines = ($tocOutput | Measure-Object -Line).Lines
 Write-Host " -> TOC valid: $tocLines entries" -ForegroundColor Green
} catch {
 Write-Host " -> Integrity check FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# ── 4. Restore to disposable database ──────────────────────────────────
Write-Host "`n[4/6] Restoring to disposable database '$VerifyDb'..." -ForegroundColor Yellow
try {
 # Drop if exists from previous run
 docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $VerifyDb;" 2>&1 | Out-Null
 docker exec $ContainerName psql -U $DbUser -d postgres -c "CREATE DATABASE $VerifyDb OWNER $DbUser;"
 if ($LASTEXITCODE -ne 0) { throw "CREATE DATABASE failed" }

 docker exec $ContainerName pg_restore -U $DbUser -d $VerifyDb --no-owner --no-acl "/tmp/$backupFile" 2>&1
 # Populate statistics in freshly restored verification database
 docker exec $ContainerName psql -U $DbUser -d $VerifyDb -c "ANALYZE;" 2>&1 | Out-Null
 Write-Host " -> Restore complete" -ForegroundColor Green
} catch {
 Write-Host " -> Restore FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# ── 5. Row count comparison ────────────────────────────────────────────
Write-Host "`n[5/6] Comparing table row counts..." -ForegroundColor Yellow
try {
 $countQuery = @"
SELECT schemaname || '.' || relname AS table_name, n_live_tup
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY relname;
"@
 $originalCounts = Invoke-Psql -Query $countQuery -Database $DbName
 $verifyCounts = Invoke-Psql -Query $countQuery -Database $VerifyDb

 $mismatch = $false
 $origMap = @{}
 foreach ($line in ($originalCounts -split "`n" | Where-Object { $_ -match '\|' })) {
 $parts = $line -split '\|'
 $origMap[$parts[0].Trim] = $parts[1].Trim
 }

 $verifyMap = @{}
 foreach ($line in ($verifyCounts -split "`n" | Where-Object { $_ -match '\|' })) {
 $parts = $line -split '\|'
 $verifyMap[$parts[0].Trim] = $parts[1].Trim
 }

 foreach ($table in $origMap.Keys) {
 $origCount = $origMap[$table]
 $verifyCount = if ($verifyMap.ContainsKey($table)) { $verifyMap[$table] } else { "MISSING" }
 if ($origCount -ne $verifyCount) {
 Write-Host " MISMATCH: $table (original=$origCount, restored=$verifyCount)" -ForegroundColor Red
 $mismatch = $true
 }
 }

 if ($origMap.Count -eq 0) {
 Write-Host " -> Row count comparison FAILED: No tables found in source database '$DbName'" -ForegroundColor Red
 $allPassed = $false
 } elseif ($mismatch) {
 Write-Host " -> Row count comparison FAILED" -ForegroundColor Red
 $allPassed = $false
 } else {
 Write-Host " -> All table row counts match ($($origMap.Count) tables)" -ForegroundColor Green
 }
} catch {
 Write-Host " -> Row count comparison FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# ── 6. Flyway schema version parity ────────────────────────────────────
Write-Host "`n[6/6] Checking Flyway schema version parity..." -ForegroundColor Yellow
try {
 $flywayQuery = "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

 $origFlyway = Invoke-Psql -Query $flywayQuery -Database $DbName
 $verifyFlyway = Invoke-Psql -Query $flywayQuery -Database $VerifyDb

 $versionCount = ($origFlyway -split "`n" | Where-Object { $_.Trim -ne "" }).Count
 if ($versionCount -eq 0) {
 Write-Host " -> Flyway check FAILED: No migrations found in flyway_schema_history" -ForegroundColor Red
 $allPassed = $false
 } elseif ($origFlyway.Trim -eq $verifyFlyway.Trim) {
 Write-Host " -> Flyway history matches ($versionCount migrations)" -ForegroundColor Green
 } else {
 Write-Host " -> Flyway schema history MISMATCH" -ForegroundColor Red
 Write-Host " Original:" -ForegroundColor DarkYellow
 Write-Host $origFlyway
 Write-Host " Restored:" -ForegroundColor DarkYellow
 Write-Host $verifyFlyway
 $allPassed = $false
 }
} catch {
 Write-Host " -> Flyway check FAILED: $_" -ForegroundColor Red
 $allPassed = $false
}

# ── Cleanup ─────────────────────────────────────────────────────────────
Write-Host "`n[Cleanup] Dropping disposable database '$VerifyDb'..." -ForegroundColor Yellow
try {
 docker exec $ContainerName psql -U $DbUser -d postgres -c "DROP DATABASE IF EXISTS $VerifyDb;" 2>&1 | Out-Null
 docker exec $ContainerName sh -c "rm -f /tmp/$backupFile" 2>&1 | Out-Null
 Write-Host " -> Cleanup complete" -ForegroundColor Green
} catch {
 Write-Host " -> Cleanup warning: $_" -ForegroundColor DarkYellow
}

# ── Result ──────────────────────────────────────────────────────────────
Write-Host "`n==========================================" -ForegroundColor Cyan
if ($allPassed) {
 Write-Host " BACKUP & RESTORE PROOF: PASSED" -ForegroundColor Green
 Write-Host " Backup file: ${BackupDir}\${backupFile}" -ForegroundColor Green
 exit 0
} else {
 Write-Host " BACKUP & RESTORE PROOF: FAILED" -ForegroundColor Red
 exit 1
}

