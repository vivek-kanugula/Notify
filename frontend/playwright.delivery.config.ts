import { defineConfig } from '@playwright/test';

// Opt-in email-delivery journeys. API-level (no browser, no web server).
//
// These tests sign up fresh tenants and need the email worker ENABLED with the FAKE provider, so they must only ever
// run against a disposable, isolated stack. Run them through scripts/e2e-delivery.ps1, which owns that stack and sets
// the two variables below. The ordinary `npm run test:e2e` (testDir ./e2e) never includes this directory.
const baseURL = process.env.NOTIFY_E2E_BASE_URL;
if (process.env.NOTIFY_E2E_ISOLATED !== '1' || !baseURL) {
  throw new Error(
    'The delivery journeys are opt-in and run only against an isolated stack. ' +
      'Use scripts/e2e-delivery.ps1 (it sets NOTIFY_E2E_ISOLATED=1 and NOTIFY_E2E_BASE_URL).',
  );
}
const host = new URL(baseURL).hostname;
if (!['localhost', '127.0.0.1', '[::1]'].includes(host)) {
  throw new Error('NOTIFY_E2E_BASE_URL must be a loopback address.');
}

export default defineConfig({
  testDir: './e2e-delivery',
  testMatch: /.*\.spec\.ts/,
  timeout: 150_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: { baseURL },
});
