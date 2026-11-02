<#
.SYNOPSIS
 Runs the opt-in Playwright journeys against a disposable, isolated Notify stack that this run creates and removes.

.DESCRIPTION
 Starts its own PostgreSQL 16, Redis 7 and Kafka 3.8 containers (random free loopback ports, random password, unique
 run id) and the packaged backend jar on a free loopback port, runs Playwright against it, and removes only what this
 run created. It never targets the development PostgreSQL/Redis/Kafka or a shared application, and it refuses to start
 when a port is taken or a container with an intended name already exists.

 Suites
 Delivery frontend/e2e-delivery (worker ENABLED, FAKE provider; API-level journeys) default
 Ui frontend/e2e (worker DISABLED, the ordinary browser journeys; needs system Chrome)
 -UiWorkers enables the workers on the Ui suite's own stack, for the browser
 journey that must recover from more than 100 missed in-app messages
 All Ui, then Delivery, each on its own freshly created stack

 Exit codes
 0 passed | 1 a Playwright test failed | 2 environment/startup/readiness failure (Docker, container, jar, backend)
 3 refused by a precondition (port or name taken, jar or frontend dependencies missing) | 4 tests passed but cleanup failed

 Cleanup of an interrupted run: -CleanupRunId <id> removes only containers labelled with that run id by this script and
 stops only the recorded backend process (its command line is verified first).

.EXAMPLE
 backend\mvnw.cmd -f backend\pom.xml -DskipTests package
 scripts\e2e-delivery.ps1 -Suite Delivery

.EXAMPLE
 scripts\e2e-delivery.ps1 -Suite All -JarPath D:\build\notify-backend.jar
#>
[CmdletBinding]
param(
 [ValidateSet('Delivery', 'Ui', 'All')][string]$Suite = 'Delivery',
 [string]$JarPath = '',
 [string]$WorkDir = '',
 [int]$BackendPort = 0,
 [int]$StartupTimeoutSeconds = 150,
 [string]$CleanupRunId = '',
 # corrective cycle: the browser journey that recovers from more than one hundred messages missed while
 # offline needs deliveries to actually happen, and the Ui suite's stack normally runs with every worker disabled.
 # This switch enables them for the Ui suite's own disposable stack and nothing else; without it every existing run
 # behaves exactly as before, and the journey declares itself skipped rather than passing vacuously.
 [switch]$UiWorkers,
 # Restricts the Ui suite to the journeys whose title matches. Needed with -UiWorkers: journeys written for a
 # stack where nothing is delivered (an audit trail with exactly one transition, for example) legitimately fail
 # when deliveries start happening, so the worker-enabled run is scoped to the journey that needs them.
 [string]$UiGrep = ''
)

Set-StrictMode -Version Latest
# Native tools are checked through exit codes; PowerShell 5.1 must not turn their stderr into terminating errors.
$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $repoRoot 'frontend'
$ownerLabel = 'notify-e2e-delivery-runner'
# Ports of the shared development services and applications: never allocated, never targeted.
$reservedPorts = @(3000, 5173, 5432, 6379, 8080, 8081, 9092, 9093, 15432, 16379, 19092)

$docker = $null
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCmd) { $docker = $dockerCmd.Source }

$script:runId = $null
$script:state = $null
$script:secrets = @
$script:allocatedPorts = @
$script:exitCode = 0
$script:playwrightExit = 1
$script:backendProc = $null

# --- helpers -------------------------------------------------------------------------------------------------------

function Stop-Run([int]$Code, [string]$Message) {
 $ex = New-Object System.InvalidOperationException($Message)
 $ex.Data['ExitCode'] = $Code
 throw $ex
}

function Mask([string]$Text) {
 if ([string]::IsNullOrEmpty($Text)) { return $Text }
 foreach ($s in $script:secrets) { if ($s) { $Text = $Text.Replace($s, '***') } }
 return $Text
}

function ConvertTo-ArgString([string]$Items) {
 ($Items | ForEach-Object {
 if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
 }) -join ' '
}

# Runs a native tool without PowerShell's native-error handling. $Env values reach only the child process.
function Invoke-Tool {
 param([string]$File, [string]$Arguments, [int]$TimeoutSeconds = 120, [hashtable]$Env = @{})
 $psi = New-Object System.Diagnostics.ProcessStartInfo
 $psi.FileName = $File
 $psi.Arguments = ConvertTo-ArgString $Arguments
 $psi.RedirectStandardOutput = $true
 $psi.RedirectStandardError = $true
 $psi.UseShellExecute = $false
 $psi.CreateNoWindow = $true
 foreach ($k in $Env.Keys) { $psi.EnvironmentVariables[$k] = [string]$Env[$k] }
 $p = [System.Diagnostics.Process]::Start($psi)
 $out = $p.StandardOutput.ReadToEndAsync
 $err = $p.StandardError.ReadToEndAsync
 if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
 try { $p.Kill } catch { }
 return [pscustomobject]@{ ExitCode = 124; Out = ''; Err = 'timed out' }
 }
 $p.WaitForExit
 return [pscustomobject]@{ ExitCode = $p.ExitCode; Out = $out.Result.Trim; Err = $err.Result.Trim }
}

function Invoke-Docker([string]$Arguments, [hashtable]$Env = @{}, [int]$TimeoutSeconds = 120) {
 return Invoke-Tool -File $docker -Arguments $Arguments -Env $Env -TimeoutSeconds $TimeoutSeconds
}

function Invoke-WithEnv([hashtable]$Vars, [scriptblock]$Body) {
 $saved = @{}
 foreach ($k in $Vars.Keys) {
 $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
 [Environment]::SetEnvironmentVariable($k, [string]$Vars[$k], 'Process')
 }
 try { & $Body } finally {
 foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
 }
}

function Test-PortInUse([int]$Port) {
 if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return $true }
 try {
 $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
 $l.Start; $l.Stop
 return $false
 } catch { return $true }
}

function Get-FreePort {
 for ($i = 0; $i -lt 50; $i++) {
 $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
 $l.Start; $port = $l.LocalEndpoint.Port; $l.Stop
 if ($reservedPorts -notcontains $port -and $script:allocatedPorts -notcontains $port -and -not (Test-PortInUse $port)) {
 $script:allocatedPorts += $port
 return $port
 }
 }
 Stop-Run 2 'Could not allocate a free loopback port.'
}

function New-Secret {
 $bytes = New-Object byte 18
 [System.Security.Cryptography.RandomNumberGenerator]::Create.GetBytes($bytes)
 return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

# AES-256 key for the webhook signing secrets of this run only . It is generated per run, never stored, and
# never printed: the run's database is removed with the run.
function New-WebhookKey {
 $bytes = New-Object byte 32
 [System.Security.Cryptography.RandomNumberGenerator]::Create.GetBytes($bytes)
 $key = [Convert]::ToBase64String($bytes)
 $script:secrets += $key
 return $key
}

function Save-State {
 if ($script:state -and $script:state.workDir) {
 $script:state | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $script:state.workDir 'run.json') -Encoding UTF8
 }
}

function Test-ContainerOwnedByRun([string]$Id, [string]$RunId) {
 $r = Invoke-Docker @('inspect', '--format', '{{ index .Config.Labels "notify.e2e.run" }}|{{ index .Config.Labels "notify.e2e.owner" }}', $Id)
 return ($r.ExitCode -eq 0 -and $r.Out -eq "$RunId|$ownerLabel")
}

# --- resources -----------------------------------------------------------------------------------------------------

function New-RunContainer([string]$Role, [string]$RunArgs, [hashtable]$Env = @{}) {
 $name = "notify-e2e-$($script:runId)-$Role"
 $existing = Invoke-Docker @('ps', '-a', '--filter', ('name=^/{0}$' -f $name), '--format', '{{.ID}}')
 if ($existing.Out) { Stop-Run 3 "A container named $name already exists; refusing to touch it." }
 $args = @('run', '-d', '--name', $name, '--label', "notify.e2e.run=$($script:runId)", '--label', "notify.e2e.owner=$ownerLabel") + $RunArgs
 $r = Invoke-Docker $args $Env
 if ($r.ExitCode -ne 0) { Stop-Run 2 ("docker run failed for $Role : " + (Mask $r.Err)) }
 $id = ($r.Out -split "`n")[-1].Trim
 $script:state.containers += [pscustomobject]@{ role = $Role; name = $name; id = $id }
 Save-State
 return $id
}

function Wait-Until([scriptblock]$Condition, [int]$TimeoutSeconds, [string]$What) {
 $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
 while ((Get-Date) -lt $deadline) {
 if (& $Condition) { return }
 Start-Sleep -Seconds 2
 }
 Stop-Run 2 "Timed out after ${TimeoutSeconds}s waiting for $What."
}

function Start-Infra {
 $pw = New-Secret
 $script:secrets += $pw
 $pgPort = Get-FreePort; $redisPort = Get-FreePort; $kafkaPort = Get-FreePort
 $script:state.ports = @{ postgres = $pgPort; redis = $redisPort; kafka = $kafkaPort }

 $pg = New-RunContainer 'pg' @('-e', 'POSTGRES_USER=e2e_user', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_DB=notify_e2e',
 '-p', "127.0.0.1:${pgPort}:5432", 'postgres:16-alpine') @{ POSTGRES_PASSWORD = $pw }
 $redis = New-RunContainer 'redis' @('-p', "127.0.0.1:${redisPort}:6379", 'redis:7-alpine')
 $kafka = New-RunContainer 'kafka' @(
 '-p', "127.0.0.1:${kafkaPort}:9092",
 '-e', 'KAFKA_NODE_ID=1', '-e', 'KAFKA_PROCESS_ROLES=broker,controller',
 '-e', 'KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093',
 '-e', "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:$kafkaPort",
 '-e', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
 '-e', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
 '-e', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093',
 '-e', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1', '-e', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
 '-e', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1', '-e', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0',
 '-e', 'KAFKA_NUM_PARTITIONS=3', '-e', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE=false',
 'apache/kafka:3.8.0')

 Wait-Until { (Invoke-Docker @('exec', $pg, 'pg_isready', '-U', 'e2e_user', '-d', 'notify_e2e')).Out -match 'accepting' } 90 'the isolated PostgreSQL'
 Wait-Until { (Invoke-Docker @('exec', $redis, 'redis-cli', 'ping')).Out -match 'PONG' } 60 'the isolated Redis'
 # The broker advertises its host-mapped address, which is not reachable from inside the container, so an in-container
 # admin call cannot be used; the broker's own start-up log line is the readiness signal.
 Wait-Until {
 $logs = Invoke-Docker @('logs', $kafka)
 (($logs.Out + $logs.Err) -match 'Kafka Server started')
 } 90 'the isolated Kafka'
 return $pw
}

function Resolve-Jar {
 if ($JarPath) {
 if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { Stop-Run 3 "Jar not found: $JarPath" }
 return (Resolve-Path -LiteralPath $JarPath).Path
 }
 $found = @(Get-ChildItem -Path (Join-Path $repoRoot 'backend\target') -Filter '*.jar' -ErrorAction SilentlyContinue |
 Where-Object { $_.Name -notlike '*.original' })
 if ($found.Count -ne 1) {
 Stop-Run 3 'Expected exactly one jar in backend\target. Build it first (backend\mvnw.cmd -DskipTests package) or pass -JarPath.'
 }
 return $found[0].FullName
}

# Checked before any container is created, so a refusal leaves nothing behind.
function Resolve-BackendPort {
 $port = $BackendPort
 if ($port -gt 0) {
 if ($reservedPorts -contains $port) { Stop-Run 3 "Port $port belongs to a shared development service and is never used." }
 if (Test-PortInUse $port) { Stop-Run 3 "Backend port $port is already in use; refusing to reuse an unrelated server." }
 $script:allocatedPorts += $port
 return $port
 }
 return (Get-FreePort)
}

function Start-Backend([string]$Jar, [string]$DbPassword, [bool]$WorkerEnabled, [int]$UiPort, [int]$port) {
 $ports = $script:state.ports
 $envVars = @{
 SPRING_PROFILES_ACTIVE = 'local'
 SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$($ports.postgres)/notify_e2e"
 SPRING_DATASOURCE_USERNAME = 'e2e_user'
 SPRING_DATASOURCE_PASSWORD = $DbPassword
 SPRING_DATA_REDIS_HOST = '127.0.0.1'
 SPRING_DATA_REDIS_PORT = "$($ports.redis)"
 SPRING_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:$($ports.kafka)"
 NOTIFY_WORKER_EMAIL_ENABLED = "$WorkerEnabled".ToLowerInvariant
 NOTIFY_EMAIL_PROVIDER = 'fake'
 NOTIFY_RESEND_API_KEY = ''
 # the webhook worker runs in the Delivery suite only, and its only destination is this run's own
 # backend, on loopback. The demo URL is what makes that endpoint is_demo, which is the only reason plain http
 # to a loopback address is allowed . Nothing outside this machine is contacted.
 NOTIFY_WORKER_WEBHOOK_ENABLED = "$WorkerEnabled".ToLowerInvariant
 NOTIFY_WEBHOOK_SECRET_KEY = (New-WebhookKey)
 # the in-app worker runs in the Delivery suite too. It needs no key and no external destination -
 # an in-app delivery is a row in this run's own disposable PostgreSQL plus a Redis hint on this run's own
 # disposable Redis.
 NOTIFY_WORKER_INAPP_ENABLED = "$WorkerEnabled".ToLowerInvariant
 NOTIFY_OUTBOX_RELAY_ENABLED = if ($WorkerEnabled) { 'true' } else { 'false' }
 NOTIFY_RECONCILER_ENABLED = if ($WorkerEnabled) { 'true' } else { 'false' }
 }
 if ($WorkerEnabled) {
 $envVars['NOTIFY_DEMO_WEBHOOK_URL'] = "http://127.0.0.1:$port/demo/webhook-receiver"
 $envVars['NOTIFY_WEBHOOK_DEMO_ALLOWLIST'] = '127.0.0.1,localhost'
 }
 $logOut = Join-Path $script:state.workDir 'backend.out.log'
 $logErr = Join-Path $script:state.workDir 'backend.err.log'
 $publicUrl = if ($UiPort -gt 0) { "http://localhost:$UiPort" } else { "http://127.0.0.1:$port" }
 $javaArgs = @('-jar', $Jar, "--server.port=$port", "--notify.public-url=$publicUrl", "--notify.frontend-url=http://localhost:$UiPort")
 $proc = $null
 Invoke-WithEnv $envVars {
 $script:backendProc = Start-Process -FilePath 'java' -ArgumentList $javaArgs -PassThru -WindowStyle Hidden `
 -RedirectStandardOutput $logOut -RedirectStandardError $logErr
 }
 $proc = $script:backendProc
 $script:state.backend = [pscustomobject]@{ port = $port; launcherPid = $proc.Id; listenerPid = $null; jar = $Jar; workerEnabled = $WorkerEnabled }
 Save-State

 $base = "http://127.0.0.1:$port"
 $ready = $false
 $lastProbe = 'no response yet'
 $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
 while ((Get-Date) -lt $deadline -and -not $ready) {
 Start-Sleep -Seconds 2
 try {
 $r = Invoke-WebRequest -Uri "$base/actuator/health/readiness" -UseBasicParsing -TimeoutSec 3
 $lastProbe = "HTTP $($r.StatusCode)"
 # The actuator media type (application/vnd.spring-boot.actuator.*+json) reaches Windows PowerShell 5.1 as bytes.
 $body = if ($r.Content -is [byte]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
 $ready = (($r.StatusCode -eq 200) -and ($body -match '"status"\s*:\s*"UP"'))
 } catch { $lastProbe = "probe error: $($_.Exception.Message)" }
 if (-not $ready -and $proc.HasExited -and -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
 $tail = (Get-Content -Path $logErr, $logOut -Tail 15 -ErrorAction SilentlyContinue) -join "`n"
 Stop-Run 2 ("The backend exited during startup (exit code $($proc.ExitCode)). Last log lines:`n" + (Mask $tail))
 }
 }
 if (-not $ready) {
 $tail = (Get-Content -Path $logErr, $logOut -Tail 15 -ErrorAction SilentlyContinue) -join "`n"
 Stop-Run 2 ("Readiness did not become UP within ${StartupTimeoutSeconds}s at $base (last probe: $lastProbe). Last log lines:`n" + (Mask $tail))
 }

 # Whoever answers on our port must be the process we started (or its child): never trust an unrelated server.
 $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
 if (-not $listener) { Stop-Run 2 "Nothing listens on port $port after readiness." }
 $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
 $isOurs = $owner -and $owner.CommandLine -like "*--server.port=$port*" -and $owner.CommandLine -like "*$([System.IO.Path]::GetFileName($Jar))*"
 if (-not $isOurs) { Stop-Run 3 "The server answering on port $port is not the backend started by this run; refusing to continue." }
 $script:state.backend.listenerPid = $listener.OwningProcess
 Save-State
 return $base
}

function Stop-RecordedBackend($backend) {
 if (-not $backend) { return }
 $port = $backend.port
 foreach ($procId in @($backend.listenerPid, $backend.launcherPid)) {
 if (-not $procId) { continue }
 $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
 # Only a process whose command line still names this run's port is ours (guards against PID reuse).
 if ($p -and $p.CommandLine -like "*--server.port=$port*") {
 Invoke-Tool -File 'taskkill.exe' -Arguments @('/PID', "$procId", '/T', '/F') -TimeoutSeconds 30 | Out-Null
 }
 }
}

# Removes only the containers recorded for this run and still labelled with this run id; returns the failures.
function Remove-RecordedContainers($containers, [string]$RunId) {
 $failures = @
 foreach ($c in @($containers)) {
 if (-not (Test-ContainerOwnedByRun $c.id $RunId)) {
 $gone = Invoke-Docker @('ps', '-a', '--filter', "id=$($c.id)", '--format', '{{.ID}}')
 if ($gone.Out) { $failures += "container $($c.name) is no longer labelled for run $RunId; left in place" }
 continue
 }
 $r = Invoke-Docker @('rm', '-f', '-v', $c.id)
 if ($r.ExitCode -ne 0) { $failures += "could not remove $($c.name): $(Mask $r.Err)" }
 }
 return $failures
}

function Remove-RunResources {
 $failures = @
 if ($script:state) {
 try { Stop-RecordedBackend $script:state.backend } catch { $failures += "backend stop failed: $($_.Exception.Message)" }
 $failures += @(Remove-RecordedContainers $script:state.containers $script:runId)
 $script:state.containers = @
 $script:state.backend = $null
 Save-State
 }
 return $failures
}

function Write-IsolationEvidence([bool]$WorkerEnabled) {
 $pg = $script:state.containers | Where-Object { $_.role -eq 'pg' } | Select-Object -First 1
 if ($pg) {
 $q = Invoke-Docker @('exec', $pg.id, 'psql', '-U', 'e2e_user', '-d', 'notify_e2e', '-tAc',
 'select count(*) || '' migrations, last version '' || max(version) from flyway_schema_history where success')
 if ($q.ExitCode -eq 0) { Write-Host " isolated database: $($q.Out)" }
 }
 if ($pg) {
 $n = Invoke-Docker @('exec', $pg.id, 'psql', '-U', 'e2e_user', '-d', 'notify_e2e', '-tAc',
 'select coalesce(string_agg(status || '': '' || c, '', '' order by status), ''none'') from (select status, count(*) c from notifications group by status) t')
 if ($n.ExitCode -eq 0) { Write-Host " isolated database notifications by status: $($n.Out)" }
 }
}

function Invoke-Playwright([string]$Name, [hashtable]$EnvVars, [string]$PlaywrightArgs) {
 if (-not (Test-Path (Join-Path $frontendDir 'node_modules\@playwright\test'))) {
 Stop-Run 3 'frontend dependencies are missing; run "npm ci" in the frontend directory first.'
 }
 Write-Host "Running Playwright suite '$Name'..."
 Invoke-WithEnv $EnvVars {
 Push-Location $frontendDir
 try {
 & npx.cmd playwright test @PlaywrightArgs | Out-Host
 $script:playwrightExit = $LASTEXITCODE
 } finally { Pop-Location }
 }
 return $script:playwrightExit
}

# One suite = one fresh stack: containers, backend, tests, cleanup.
function Invoke-Suite([string]$Name) {
 $workerEnabled = ($Name -eq 'Delivery') -or ($Name -eq 'Ui' -and $UiWorkers)
 $script:runId = ('e2e' + (New-Secret).Substring(0, 8))
 $baseDir = if ($WorkDir) { $WorkDir } else { Join-Path ([System.IO.Path]::GetTempPath) 'notify-e2e' }
 $runDir = Join-Path $baseDir $script:runId
 New-Item -ItemType Directory -Path $runDir -Force | Out-Null
 $script:state = [ordered]@{ runId = $script:runId; suite = $Name; workDir = $runDir; startedAt = (Get-Date).ToString('o'); containers = @; backend = $null; ports = @{} }
 Save-State
 Write-Host "== Suite $Name | run id $($script:runId) | work dir $runDir =="

 $suiteCode = 0
 try {
 $jar = Resolve-Jar
 $backendPort = Resolve-BackendPort
 $uiPort = Get-FreePort
 $pw = Start-Infra
 $base = Start-Backend $jar $pw $workerEnabled $uiPort $backendPort
 Write-Host " backend ready at $base (worker enabled: $workerEnabled, provider: fake)"

 if ($Name -eq 'Delivery') {
 $suiteCode = Invoke-Playwright 'Delivery' @{ NOTIFY_E2E_ISOLATED = '1'; NOTIFY_E2E_BASE_URL = $base; NO_COLOR = '' } @('-c', 'playwright.delivery.config.ts')
 } else {
 $uiArgs = if ($UiGrep) { @('--grep', $UiGrep) } else { @ }
 $playwrightEnv = @{ NOTIFY_E2E_BACKEND_URL = $base; NOTIFY_E2E_UI_PORT = "$uiPort"; NO_COLOR = '' }
 $suiteCode = Invoke-Playwright 'Ui' $playwrightEnv $uiArgs
 }
 Write-IsolationEvidence $workerEnabled
 if ($suiteCode -ne 0) { $script:exitCode = 1; Write-Host "Suite $Name FAILED (Playwright exit code $suiteCode)." -ForegroundColor Red }
 else { Write-Host "Suite $Name PASSED." -ForegroundColor Green }
 } catch {
 $code = 2
 if ($_.Exception.Data.Contains('ExitCode')) { $code = [int]$_.Exception.Data['ExitCode'] }
 Write-Host ("Suite $Name aborted: " + (Mask $_.Exception.Message)) -ForegroundColor Red
 if ($script:exitCode -eq 0) { $script:exitCode = $code }
 } finally {
 $hadResources = ($script:state.containers.Count -gt 0) -or ($null -ne $script:state.backend)
 $failures = @(Remove-RunResources)
 if ($failures.Count -gt 0) {
 foreach ($f in $failures) { Write-Host " cleanup: $f" -ForegroundColor Yellow }
 if ($script:exitCode -eq 0) { $script:exitCode = 4 }
 } elseif ($hadResources) {
 Write-Host " cleanup: removed containers and backend process created by run $($script:runId)."
 }
 }
}

# --- entry ---------------------------------------------------------------------------------------------------------

try {
 if (-not $docker) { Stop-Run 2 'Docker CLI not found on PATH.' }
 $info = Invoke-Docker @('info', '--format', '{{.ServerVersion}}') -TimeoutSeconds 30
 if ($info.ExitCode -ne 0) { Stop-Run 2 'Docker daemon is not reachable.' }

 if ($CleanupRunId) {
 $id = $CleanupRunId
 if ($id -notmatch '^e2e[0-9a-f]{8}$') { Stop-Run 3 'Not a run id created by this script.' }
 $baseDir = if ($WorkDir) { $WorkDir } else { Join-Path ([System.IO.Path]::GetTempPath) 'notify-e2e' }
 $stateFile = Join-Path (Join-Path $baseDir $id) 'run.json'
 $found = Invoke-Docker @('ps', '-a', '--filter', "label=notify.e2e.run=$id", '--filter', "label=notify.e2e.owner=$ownerLabel", '--format', '{{.ID}}|{{.Names}}')
 $containers = @
 foreach ($line in ($found.Out -split "`n")) {
 if ($line.Trim) { $parts = $line.Trim.Split('|'); $containers += [pscustomobject]@{ id = $parts[0]; name = $parts[1] } }
 }
 $backend = $null
 if (Test-Path $stateFile) {
 $stateData = Get-Content $stateFile -Raw | ConvertFrom-Json
 $backend = $stateData.backend
 }
 Stop-RecordedBackend $backend
 $f = @(Remove-RecordedContainers $containers $id)
 $f | ForEach-Object { Write-Host " cleanup: $_" -ForegroundColor Yellow }
 Write-Host "Cleanup of run $id finished ($($containers.Count) container(s) matched)."
 $script:exitCode = if ($f.Count -gt 0) { 4 } else { 0 }
 } else {
 $suites = if ($Suite -eq 'All') { @('Ui', 'Delivery') } else { @($Suite) }
 foreach ($s in $suites) {
 Invoke-Suite $s
 if ($script:exitCode -ge 2 -and $script:exitCode -ne 4) { break }
 }
 }
} catch {
 $code = 2
 if ($_.Exception.Data.Contains('ExitCode')) { $code = [int]$_.Exception.Data['ExitCode'] }
 Write-Host ("e2e-delivery: " + (Mask $_.Exception.Message)) -ForegroundColor Red
 $script:exitCode = $code
}

exit $script:exitCode
