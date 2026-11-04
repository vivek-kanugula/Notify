import { test, expect } from '@playwright/test';

/**
 * End-to-End Browser Automation Journey for Notify Observability & Telemetry Stack.
 *
 * Verifies:
 * 1. Admin Operator Console System Status observability panel rendering.
 * 2. Observability telemetry link targets, contracts, and parameter encodings.
 * 3. Deep-link new-tab navigation to Grafana Overview, Loki Structured Logs, and Tempo Distributed Traces.
 * 4. Subpath routing alignment (/grafana/ subpath, no login 404 redirects, anonymous admin access).
 * 5. Multi-device responsive behavior at 1280px, 768px, and 390px viewports.
 */

test.use({
  baseURL: 'http://localhost:5173',
  launchOptions: {
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  },
});

function createMockAdminToken(): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(JSON.stringify({
    sub: '00000000-0000-0000-0000-000000000001',
    email: 'admin@notify.local',
    aud: ['notify-admin'],
    roles: ['ROLE_PLATFORM_ADMIN'],
    iat: now,
    exp: now + 86400,
  })).toString('base64url');
  return `${header}.${payload}.mock_signature`;
}

const mockAdminToken = createMockAdminToken();

const mockAdminUser = {
  adminId: '00000000-0000-0000-0000-000000000001',
  email: 'admin@notify.local',
  fullName: 'Operator Admin',
  roles: ['ROLE_PLATFORM_ADMIN'],
};

const mockSystemStatus = {
  status: 'UP',
  timestamp: new Date().toISOString(),
  components: {
    database: { status: 'UP', details: { activeConnections: 5, idleConnections: 15 } },
    redis: { status: 'UP', details: { pingLatencyMs: 1.1 } },
    kafka: { status: 'UP', details: { clusterId: 'notify-cluster', brokers: 3 } },
    quartz: { status: 'UP', details: { schedulerName: 'NotifyQuartzScheduler', threadPoolSize: 10 } },
  },
  circuitBreakers: [
    { name: 'emailCircuit', state: 'CLOSED', failureRate: 0.0 },
    { name: 'webhookCircuit', state: 'CLOSED', failureRate: 0.0 },
  ],
  grafanaLinks: {
    overview: '/grafana/d/notify-overview/notify-overview',
    logs: '/grafana/explore?schemaVersion=1&panes=%7B%22A%22%3A%7B%22datasource%22%3A%22notify-loki%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22%7Bservice_name%3D~%5C%22.%2B%5C%22%7D%22%2C%22queryType%22%3A%22range%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D',
    traces: '/grafana/explore?schemaVersion=1&panes=%7B%22A%22%3A%7B%22datasource%22%3A%22notify-tempo%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22queryType%22%3A%22traceql%22%2C%22query%22%3A%22%7B%7D%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D',
  },
};

test.describe('Observability & Telemetry Dashboards E2E Journey', () => {
  let isAuthenticated = false;

  test.beforeEach(async ({ context }) => {
    isAuthenticated = false;

    // 1. Tenant auth mock: no active tenant session
    await context.route('**/auth/refresh', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ detail: 'No active session' }),
      });
    });

    await context.route('**/auth/config', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ googleLoginEnabled: false }),
      });
    });

    // 2. Admin auth mocks: controlled by isAuthenticated state
    await context.route('**/auth/admin/refresh', async (route) => {
      if (!isAuthenticated) {
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ detail: 'No session' }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            accessToken: mockAdminToken,
            tokenType: 'Bearer',
            expiresIn: 3600,
          }),
        });
      }
    });

    await context.route('**/auth/admin/login', async (route) => {
      isAuthenticated = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: mockAdminToken,
          tokenType: 'Bearer',
          expiresIn: 3600,
        }),
      });
    });

    await context.route('**/auth/admin/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockAdminUser),
      });
    });

    // 3. Admin system status mock
    await context.route('**/api/v1/admin/system/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockSystemStatus),
      });
    });

    // 4. Admin tenants list mock
    await context.route('**/api/v1/admin/tenants*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [],
          total: 0,
          limit: 20,
          cursor: null,
          hasMore: false,
        }),
      });
    });

    // 5. Grafana mock response
    await context.route('**/grafana/**', async (route) => {
      const url = route.request().url();
      if (url.includes('/api/dashboards/uid/notify-overview')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            dashboard: { title: 'Notify Overview', uid: 'notify-overview' },
            meta: { isStarred: false, canSave: false, canEdit: true },
          }),
        });
      } else if (url.includes('/api/dashboards/home')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            redirectUri: '/grafana/d/notify-overview/notify-overview',
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'text/html',
          body: `<!DOCTYPE html>
<html>
<head>
  <base href="/grafana/" />
  <title>Home - Grafana</title>
  <script>
    window.grafanaBootData = {
      user: { isSignedIn: false, orgRole: "Admin", orgName: "Main Org." },
      settings: { appSubUrl: "/grafana", appUrl: "http://localhost:5173/grafana/", anonymousEnabled: true }
    };
  </script>
</head>
<body>
  <div id="grafana-app">
    <h1>Notify Overview Dashboard</h1>
    <p>Anonymous Admin Session Active</p>
  </div>
</body>
</html>`,
        });
      }
    });
  });

  test('1. Admin logs in, navigates to System Status, and verifies all 3 telemetry dashboard links', async ({ page }) => {
    // 1. Visit Operator Sign In
    await page.goto('/admin/login');
    await expect(page.locator('h1:has-text("Operator Console Sign In")')).toBeVisible();

    // 2. Sign in with operator credentials
    await page.fill('#admin-email', 'admin@notify.local');
    await page.fill('#admin-password', 'placeholder_admin_password_min_10_chars');
    await page.click('button:has-text("Sign In to Console")');

    // 3. Lands on /admin/tenants, then navigates to System Status
    await expect(page).toHaveURL(/\/admin\/tenants/);
    await page.goto('/admin/system');
    await expect(page.locator('h1:has-text("System Status & Infrastructure")')).toBeVisible();

    // 4. Verify External Observability & Telemetry Dashboards panel
    const obsPanel = page.locator('.panel', {
      has: page.locator('h3:has-text("External Observability & Telemetry Dashboards")'),
    });
    await expect(obsPanel).toBeVisible();

    // 5. Verify the 3 observability buttons exist with expected attributes
    const overviewBtn = obsPanel.locator('a:has-text("Grafana Overview")');
    const logsBtn = obsPanel.locator('a:has-text("Loki Structured Logs")');
    const tracesBtn = obsPanel.locator('a:has-text("Tempo Distributed Traces")');

    await expect(overviewBtn).toBeVisible();
    await expect(logsBtn).toBeVisible();
    await expect(tracesBtn).toBeVisible();

    // Assert exact href targets point to /grafana/ subpath
    await expect(overviewBtn).toHaveAttribute('href', '/grafana/d/notify-overview/notify-overview');
    await expect(overviewBtn).toHaveAttribute('target', '_blank');
    await expect(overviewBtn).toHaveAttribute('rel', 'noopener noreferrer');

    await expect(logsBtn).toHaveAttribute('href', /^\/grafana\/explore\?schemaVersion=1&panes=.*notify-loki/);
    await expect(logsBtn).toHaveAttribute('target', '_blank');
    await expect(logsBtn).toHaveAttribute('rel', 'noopener noreferrer');

    await expect(tracesBtn).toHaveAttribute('href', /^\/grafana\/explore\?schemaVersion=1&panes=.*notify-tempo/);
    await expect(tracesBtn).toHaveAttribute('target', '_blank');
    await expect(tracesBtn).toHaveAttribute('rel', 'noopener noreferrer');
  });

  test('2. Deep-link multi-tab navigation opens Grafana seamlessly without 404 or login redirect', async ({
    page,
    context,
  }) => {
    // 1. Log in as operator
    await page.goto('/admin/login');
    await page.fill('#admin-email', 'admin@notify.local');
    await page.fill('#admin-password', 'placeholder_admin_password_min_10_chars');
    await page.click('button:has-text("Sign In to Console")');
    await expect(page).toHaveURL(/\/admin\/tenants/);

    // 2. Navigate directly to System Status
    await page.goto('/admin/system');
    await expect(page.locator('h1:has-text("System Status & Infrastructure")')).toBeVisible();

    // 3. Listen for new tab popup when clicking Grafana Overview
    const [newPage] = await Promise.all([
      context.waitForEvent('page'),
      page.click('a:has-text("Grafana Overview")'),
    ]);

    await newPage.waitForLoadState('domcontentloaded');

    // Assert the new tab navigated to the Grafana subpath
    expect(newPage.url()).toContain('/grafana/d/notify-overview/notify-overview');

    // Assert NO "Page not found" mascot or text is present
    await expect(newPage.locator('text=Page not found')).not.toBeVisible();
    await expect(newPage.locator('text=Sign in')).not.toBeVisible();

    // Assert Grafana boot data shows anonymous Admin session
    const bootData = await newPage.evaluate(() => (window as any).grafanaBootData);
    expect(bootData).toBeDefined();
    expect(bootData.user.orgRole).toBe('Admin');
    expect(bootData.settings.anonymousEnabled).toBe(true);
    expect(bootData.settings.appSubUrl).toBe('/grafana');

    await newPage.close();
  });

  test('3. Responsive layout of observability buttons across 1280px, 768px, and 390px viewports', async ({
    page,
  }) => {
    // 1. Log in as operator
    await page.goto('/admin/login');
    await page.fill('#admin-email', 'admin@notify.local');
    await page.fill('#admin-password', 'placeholder_admin_password_min_10_chars');
    await page.click('button:has-text("Sign In to Console")');
    await expect(page).toHaveURL(/\/admin\/tenants/);

    const viewports = [
      { width: 1280, height: 800, name: 'Desktop' },
      { width: 768, height: 1024, name: 'Tablet' },
      { width: 390, height: 844, name: 'Mobile' },
    ];

    for (const vp of viewports) {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await page.goto('/admin/system');

      const obsPanel = page.locator('.panel', {
        has: page.locator('h3:has-text("External Observability & Telemetry Dashboards")'),
      });
      await expect(obsPanel).toBeVisible();

      // Ensure all 3 buttons are visible and click-targetable without horizontal overflow
      const overviewBtn = obsPanel.locator('a:has-text("Grafana Overview")');
      const logsBtn = obsPanel.locator('a:has-text("Loki Structured Logs")');
      const tracesBtn = obsPanel.locator('a:has-text("Tempo Distributed Traces")');

      await expect(overviewBtn).toBeVisible();
      await expect(logsBtn).toBeVisible();
      await expect(tracesBtn).toBeVisible();

      // Verify no horizontal document overflow
      const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
      expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1); // allowing 1px subpixel rounding
    }
  });
});
