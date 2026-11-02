# Delivery journeys - opt-in, isolated stack only

`b7-delivery.spec.ts` holds four API-level Playwright journeys for the email delivery worker, run over real HTTP against
a backend with the **worker enabled and the fake provider**:

1. health: liveness and readiness are `UP` with the worker enabled;
2. an accepted notification is delivered end to end (outbox, relay, Kafka, worker, provider) with history `QUEUED, PROCESSING, DELIVERED`;
3. a permanent provider failure ends `FAILED` with the provider error code;
4. retryable failures walk the durable retry path (Quartz, relay) until the attempt budget is exhausted.

They sign up fresh tenants and send notifications, so they must never run against a shared or development application.
They live in `frontend/e2e-delivery/` and use their own config (`frontend/playwright.delivery.config.ts`); the ordinary
`npm run test:e2e` (`testDir: ./e2e`) does not include them, and the delivery config refuses to load unless
`NOTIFY_E2E_ISOLATED=1` and a loopback `NOTIFY_E2E_BASE_URL` are set.

No live email is ever sent: the fake provider never opens a network connection. The live Resend check is a
separate external check and is **not** part of these journeys.

## What `scripts/e2e-delivery.ps1` does

For each suite it creates a fresh, disposable stack, runs Playwright against it, and removes what it created:

| Created by the run | Details |
|---|---|
| PostgreSQL 16, Redis 7, Kafka 3.8 containers | `notify-e2e-<runId>-pg`, `-redis`, `-kafka`; labels `notify.e2e.run=<runId>` and `notify.e2e.owner=notify-e2e-delivery-runner`; published on random free `127.0.0.1` ports; random database password passed through environment variables only (never on a command line, never written to disk) |
| Backend (the packaged jar) | `java -jar <jar> --server.port=<free port>`, hidden window, profile `local`, every datasource/Redis/Kafka setting pointed at the run's containers, `NOTIFY_EMAIL_PROVIDER=fake`, no Resend key |
| Run state | `%TEMP%\notify-e2e\<runId>\` (`run.json` without secrets, `backend.out.log`, `backend.err.log`); outside the repository, and `/.e2e-runtime/` is git-ignored if you point `-WorkDir` into the repo |

Suites: `Delivery` (default; worker enabled; `frontend/e2e-delivery`), `Ui` (worker disabled; the ordinary `frontend/e2e`
browser journeys, needs the system Chrome that `playwright.config.ts` selects), `All` (`Ui`, then `Delivery`, each on its own new stack).

Safety rules enforced by the script:

- Ports of the development services and applications (3000, 5173, 5432, 6379, 8080, 8081, 9092, 9093 and a few more) are never allocated or used; a port you request with `-BackendPort` must be free or the run is refused.
- A container whose intended name already exists is never touched (the run is refused). Cleanup removes only the container ids this run created, and only while they still carry this run's labels.
- After readiness it checks that the process listening on the backend port is the one it started (command line names the port and the jar) before any test runs.
- The backend process (and its tree) is stopped only when its recorded command line still names this run's port.
- Docker/start-up/readiness problems, refusals, test failures and cleanup failures give different non-zero exit codes; cleanup never replaces an earlier failure code.

Exit codes: `0` passed, `1` a Playwright test failed, `2` environment/start-up/readiness failure, `3` refused by a
precondition (port or name taken, jar or frontend dependencies missing), `4` tests passed but cleanup failed.

## Setup

Prerequisites: Docker Desktop running, Java on `PATH`, Node with `npm ci` done in `frontend/`, and (for `Ui`/`All`) Google Chrome.

Build the jar once (build outside an IDE-watched workspace if your IDE rebuilds `backend/target` on save):

```powershell
backend\mvnw.cmd -f backend\pom.xml -DskipTests package
```

## Run

```powershell
# the four delivery journeys (default suite); uses backend\target\*.jar when there is exactly one jar
scripts\e2e-delivery.ps1

# explicit jar, and the existing UI journeys followed by the delivery journeys
scripts\e2e-delivery.ps1 -Suite All -JarPath D:\build\notify-backend.jar

# fixed (but still free and non-reserved) backend port
scripts\e2e-delivery.ps1 -Suite Delivery -BackendPort 18080
```

Running the specs by hand with `npm run test:e2e:delivery` is only for a stack you started yourself:

```powershell
$env:NOTIFY_E2E_ISOLATED = '1'; $env:NOTIFY_E2E_BASE_URL = 'http://127.0.0.1:<port>'
npm --prefix frontend run test:e2e:delivery
```

## Cleanup

Cleanup is automatic (`finally`) and prints what it removed. If a run is killed, the run id is the first line it printed
(`== Suite ... | run id e2eXXXXXXXX ==`):

```powershell
scripts\e2e-delivery.ps1 -CleanupRunId e2eXXXXXXXX
```

This removes only containers labelled with that run id by this script and stops only the recorded backend process.
`%TEMP%\notify-e2e\<runId>\` (logs, `run.json`) can then be deleted manually. If Playwright itself was killed during the
`Ui` suite, a Vite dev server on the run's random UI port may remain; it is not tracked by the script - stop it by hand.
