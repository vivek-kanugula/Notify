import { test, expect } from '@playwright/test';

/**
 * Browser journey for the in-app inbox.
 *
 * The Ui suite runs with every delivery worker DISABLED, which is exactly the right environment for the claim this
 * journey has to prove: **the saved inbox and the stream lifecycle are independent**. Nothing delivers while this
 * runs, so what is exercised is the real browser behaviour — the page, the empty state, the recipient switch, the
 * readable stream status driven by the token API, and the reconnect loop surviving a forced disconnect — rather than
 * a message arriving. The message-arrival and catch-up paths are covered over real HTTP by the delivery suite
 * (`e2e-delivery/b9-inapp.spec.ts`) and by the backend integration tests.
 *
 * Viewports: 1280, 768 and 390 px, per the UI brief.
 */
/** True when this run is pointed at scripts/e2e-delivery.ps1's isolated backend, where the in-app worker is on. */
const ISOLATED_BACKEND = Boolean(process.env.NOTIFY_E2E_BACKEND_URL);

async function signIn(page: import('@playwright/test').Page, testId: string) {
  const email = `playwright_inbox_${testId}@notify.local`;
  await page.goto('/login');
  await expect(page.locator('form.login-form')).toBeVisible();
  await page.click('button:has-text("Don\'t have an account? Sign up")');
  await page.fill('#tenantName', `Inbox Org ${testId}`);
  await page.fill('#email', email);
  await page.fill('#password', 'Password123!');
  await page.click('button.login-button:has-text("Create Account")');
  await expect(page).toHaveURL(/\/app/, { timeout: 15_000 });
}

test.describe('In-app inbox browser journey', () => {
  test('sign in, open the inbox, see its status and keyboard operation, and survive a forced disconnect', async ({
    page,
  }) => {
    const testId = `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const email = `playwright_inbox_${testId}@notify.local`;
    const tenantName = `Inbox Org ${testId}`;
    const password = 'Password123!';

    // 1. Sign up and land in the dashboard.
    await page.goto('/login');
    await expect(page.locator('form.login-form')).toBeVisible();
    await page.click('button:has-text("Don\'t have an account? Sign up")');
    await page.fill('#tenantName', tenantName);
    await page.fill('#email', email);
    await page.fill('#password', password);
    await page.click('button.login-button:has-text("Create Account")');
    await expect(page).toHaveURL(/\/app/, { timeout: 15_000 });

    // 2. The inbox is reachable from the navigation .
    await page.click('a.nav-item:has-text("In-App Inbox")');
    await expect(page).toHaveURL(/\/app\/inbox/);
    await expect(page.getByTestId('inbox-page')).toBeVisible();

    // 3. Empty state and unread badge: the saved inbox loads with no stream involved.
    await expect(page.getByTestId('inbox-empty')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('inbox-unread')).toHaveText('0 unread');

    // 4. The stream status is readable and comes from the token API, not from EventSource.onerror.
    const status = page.getByTestId('stream-status');
    await expect(status).toBeVisible();
    await expect
      .poll(async () => (await status.textContent())?.trim(), { timeout: 20_000 })
      .toMatch(/Live|Connecting|Waiting to reconnect/);

    // 5. Keyboard operation: the recipient field is focusable and submits with Enter.
    const recipientInput = page.getByTestId('recipient-input');
    await recipientInput.focus();
    await expect(recipientInput).toBeFocused();
    await recipientInput.fill(`switched-${testId}`);
    await recipientInput.press('Enter');
    await expect(page.getByTestId('inbox-empty')).toBeVisible({ timeout: 15_000 });

    // 6. A forced disconnect: go offline, then come back. The connection loop must recover without a reload, and the
    //    status must say what is happening rather than freezing on a stale "Live".
    await page.context().setOffline(true);
    await expect
      .poll(async () => (await status.textContent())?.trim(), { timeout: 30_000 })
      .toMatch(/Waiting to reconnect|Connecting/);
    await page.context().setOffline(false);
    await expect
      .poll(async () => (await status.textContent())?.trim(), { timeout: 40_000 })
      .toMatch(/Live|Connecting|Waiting to reconnect/);
    await expect(page.getByTestId('inbox-page')).toBeVisible();

    // 7. Responsive checks at the three required widths.
    for (const viewport of [
      { width: 1280, height: 900 },
      { width: 768, height: 1024 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(viewport);
      await expect(page.getByTestId('inbox-page')).toBeVisible();
      await expect(page.getByTestId('inbox-unread')).toBeVisible();
      const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      expect(scrollWidth, `no horizontal page scroll at ${viewport.width}px`).toBeLessThanOrEqual(
        viewport.width + 1,
      );
    }
  });

  /**
   * F2 in a real browser: the token request itself must fail, not merely the stream connection.
   *
   * Going offline and coming straight back does not establish this - the loop may never have reached the token
   * request. Aborting the token route keeps it failing for as long as the test wants, which is exactly the condition
   * that previously produced an unhandled rejection and no scheduled retry: the status froze and only a page reload
   * brought the stream back.
   */
  test('a failing stream-token request is reported and recovers on its own', async ({ page }) => {
    // Longer than the suite default: the loop's own backoff reaches tens of seconds by design, and a fresh tenant
    // also pays the ≈95 s provenance barrier once it does reach the server.
    test.setTimeout(240_000);
    const testId = `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    let tokenAttempts = 0;

    // One handler for the whole test, flipped rather than removed: unrouting would also remove the counter.
    let failTokenRequests = true;
    await page.route('**/api/v1/inapp/stream-tokens', async (route) => {
      tokenAttempts++;
      if (failTokenRequests) {
        await route.abort('internetdisconnected');
      } else {
        await route.continue();
      }
    });

    await signIn(page, testId);
    await page.click('a.nav-item:has-text("In-App Inbox")');
    await expect(page.getByTestId('inbox-page')).toBeVisible();

    // The saved inbox is unaffected: it needs no token at all.
    await expect(page.getByTestId('inbox-empty')).toBeVisible({ timeout: 15_000 });

    const status = page.getByTestId('stream-status');
    await expect
      .poll(async () => (await status.textContent())?.trim(), { timeout: 20_000 })
      .toBe('Waiting to reconnect');
    await expect(page.getByTestId('stream-detail')).toContainText(/connection unavailable/i);

    // It retries on its own rather than giving up after the first failure.
    await expect.poll(() => tokenAttempts, { timeout: 20_000 }).toBeGreaterThan(1);

    // And it recovers without a reload once the request succeeds again. "Recovered" here means the client is talking
    // to the server again - not necessarily streaming: a fresh tenant's first admission arms the ≈95 s barrier, so
    // the honest post-recovery state may still be "waiting", now for a reason the SERVER gave rather than because
    // the request itself failed. That difference is the whole point of condition C2, so it is what is asserted.
    const attemptsBefore = tokenAttempts;
    failTokenRequests = false;
    await expect.poll(() => tokenAttempts, { timeout: 90_000 }).toBeGreaterThan(attemptsBefore);
    await expect
      .poll(
        // Read it without a locator: going live clears the detail entirely, and an absent element is a pass here.
        () =>
          page.evaluate(
            () => document.querySelector('[data-testid="stream-detail"]')?.textContent?.trim() ?? '',
          ),
        { timeout: 120_000, intervals: [1000] },
      )
      .not.toMatch(/connection unavailable/i);
    await expect(page.getByTestId('inbox-page')).toBeVisible();
  });

  /**
   * F3 in a real browser, with more than one hundred messages missed while the page was offline.
   *
   * Only meaningful against the isolated stack, where the in-app worker is enabled; the ordinary developer run has
   * every worker disabled, so nothing would be delivered and the journey would assert nothing.
   */
  test('more than 100 messages missed while offline are recovered without an endless resync', async ({
    page,
    request,
  }) => {
    test.skip(!ISOLATED_BACKEND, 'needs an isolated stack: it delivers 102 in-app notifications');
    test.setTimeout(300_000);

    const testId = `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const email = `playwright_offline_${testId}@notify.local`;
    const password = 'Password123!';

    // One tenant, reached two ways: the API context mints the key and sends, the browser signs in and watches. The
    // page keeps its access token in memory, so the API context cannot borrow it - it signs up itself.
    const signup = await request.post('/auth/signup', {
      data: { tenantName: `Offline Org ${testId}`, email, password },
    });
    expect(signup.status()).toBe(201);
    const { accessToken } = await signup.json();
    const keyRes = await request.post('/api/v1/keys', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { name: `inbox-key-${testId}`, scopes: ['notifications:send', 'notifications:read'] },
    });
    expect(keyRes.status()).toBe(201);
    const keyBody = await keyRes.json();
    const apiKey = (keyBody.key ?? keyBody.apiKey ?? keyBody.secret) as string;
    const recipient = `offline-${testId}`;
    const sendOne = async (subject: string) => {
      const res = await request.post('/api/v1/notifications', {
        headers: { 'X-API-Key': apiKey },
        data: { channel: 'IN_APP', recipient, subject, body: `Body of ${subject}` },
      });
      expect(res.status()).toBe(202);
    };

    const unreadNow = async () => {
      const res = await request.get(
        `/api/v1/inapp-messages?recipientRef=${encodeURIComponent(recipient)}&size=1`,
        { headers: { Authorization: `Bearer ${accessToken}` } },
      );
      return (await res.json()).unread as number;
    };

    // Probe before committing to the journey: the in-app worker is enabled on the Delivery stack and disabled on
    // the Ui one, and only an actual delivery proves which this is. Asserting against a stack that delivers nothing
    // would assert nothing, so the journey declares itself skipped instead of passing vacuously.
    await sendOne('First');
    let delivered = false;
    for (let i = 0; i < 30 && !delivered; i++) {
      await new Promise((resolve) => setTimeout(resolve, 1500));
      delivered = (await unreadNow()) === 1;
    }
    test.skip(!delivered, 'the in-app delivery worker is disabled in this stack: nothing would be delivered');

    await page.goto('/login');
    await expect(page.locator('form.login-form')).toBeVisible();
    await page.fill('#email', email);
    await page.fill('#password', password);
    await page.click('button.login-button:has-text("Sign In")');
    await expect(page).toHaveURL(/\/app/, { timeout: 15_000 });

    await page.click('a.nav-item:has-text("In-App Inbox")');
    await page.getByTestId('recipient-input').fill(recipient);
    await page.getByTestId('recipient-input').press('Enter');
    // The one delivered message, so the client holds a real cursor rather than starting from zero.
    await expect(page.getByTestId('inbox-item')).toHaveCount(1, { timeout: 60_000 });

    // Offline, then 101 deliveries: more than the catch-up budget the stream will allow on reconnect.
    await page.context().setOffline(true);
    for (let i = 0; i < 101; i++) {
      await sendOne(`Missed ${i}`);
    }
    await expect.poll(unreadNow, { timeout: 200_000, intervals: [2000, 2000, 5000] }).toBe(102);

    await page.context().setOffline(false);

    // The reconnect finds 101 eligible rows, resyncs once, recovers by pagination, and resumes on a new cursor.
    // Generous: a brand-new tenant also pays the ≈95 s provenance barrier before its first admission, which is an
    // accepted cost of the design rather than something this journey should hide.
    await expect.poll(async () => (await page.getByTestId('inbox-resyncs').textContent())?.trim(), {
      timeout: 180_000,
      intervals: [2000],
    }).toMatch(/resyncs: [1-3]$/);
    await expect(page.getByTestId('inbox-item')).toHaveCount(102, { timeout: 90_000 });
    await expect(page.getByTestId('inbox-unread')).toHaveText('102 unread');
    await expect(page.getByTestId('stream-detail')).toContainText(/recovered to \d+/, { timeout: 60_000 });

    // Not a loop: the resync count settles instead of climbing.
    const settled = (await page.getByTestId('inbox-resyncs').textContent())?.trim();
    await page.waitForTimeout(10_000);
    expect((await page.getByTestId('inbox-resyncs').textContent())?.trim()).toBe(settled);

    // And the stream keeps flowing afterwards.
    await sendOne('After recovery');
    await expect(page.getByTestId('inbox-item')).toHaveCount(103, { timeout: 90_000 });
    await expect(page.getByTestId('inbox-item').first().getByTestId('inbox-subject')).toHaveText(
      'After recovery',
    );
  });
});
