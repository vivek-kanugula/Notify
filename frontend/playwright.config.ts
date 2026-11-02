import { defineConfig } from '@playwright/test';

// Defaults are the normal developer setup (Vite on 5173 proxying to the backend on 8080, an already-running dev server
// is reused). scripts/e2e-delivery.ps1 sets NOTIFY_E2E_UI_PORT and NOTIFY_E2E_BACKEND_URL to run these same journeys
// against its own isolated backend: it then starts its own Vite on a free port and never reuses an existing server.
const uiPort = process.env.NOTIFY_E2E_UI_PORT ?? '5173';
const uiUrl = `http://localhost:${uiPort}`;
const isolatedBackend = Boolean(process.env.NOTIFY_E2E_BACKEND_URL);

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: uiUrl,
    channel: 'chrome',
    headless: true,
    trace: 'on-first-retry',
  },
  webServer: {
    command: `npm run dev -- --port ${uiPort} --strictPort`,
    url: uiUrl,
    reuseExistingServer: !process.env.CI && !isolatedBackend,
    timeout: 30_000,
  },
});
