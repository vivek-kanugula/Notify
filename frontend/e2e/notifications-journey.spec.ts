import { test, expect } from '@playwright/test';

test.describe('Notifications Interface Journey', () => {
  test('sign up, create via routed form, verify detail with full LifecycleRail, cancel from detail, and verify dense table & filter bar', async ({ page }) => {
    const testId = `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const email = `tenant_n24_${testId}@notify.local`;
    const tenantName = `Test Org ${testId}`;
    const password = 'Password123!';
    const idempotencyKey = `idem_n24_${testId}`;
    const recipient = `customer_${testId}@example.com`;
    const subject = `Welcome Package #${testId}`;
    const bodyContent = `<h1>Hello Customer!</h1><p>Your subscription #${testId} is activated.</p>`;

    // 1. Sign up new tenant
    await page.goto('/login');
    await expect(page.locator('form.login-form')).toBeVisible();

    await page.click('button:has-text("Don\'t have an account? Sign up")');
    await expect(page.locator('#tenantName')).toBeVisible();

    await page.fill('#tenantName', tenantName);
    await page.fill('#email', email);
    await page.fill('#password', password);
    await page.click('button.login-button:has-text("Create Account")');

    // 2. Wait for redirect to dashboard
    await expect(page).toHaveURL(/\/app/, { timeout: 15_000 });
    await expect(page.locator('aside.sidebar')).toBeVisible();

    // 3. Navigate to Notifications page
    await page.click('a.nav-item:has-text("Notifications")');
    await expect(page).toHaveURL(/\/app\/notifications/);
    await expect(page.locator('h1:has-text("Notifications")')).toBeVisible();

    // 4. Verify Empty State & Filter Bar
    await expect(page.locator('text=No notifications found')).toBeVisible();
    await expect(page.locator('input[placeholder="Search recipient or subject…"]')).toBeVisible();

    // 5. Navigate to Routed Create Notification Page (/app/notifications/new)
    await page.click('a:has-text("Full Form →")');
    await expect(page).toHaveURL(/\/app\/notifications\/new/);
    await expect(page.locator('h1:has-text("Dispatch Notification")')).toBeVisible();

    // Fill form
    await page.click('button:has-text("Email")');
    await page.fill('#recipient-input', recipient);
    await page.fill('input[placeholder="Notification Subject"]', subject);
    await page.fill('textarea[placeholder="Notification content / payload..."]', bodyContent);

    // Generate Idempotency UUID
    await page.click('button:has-text("Generate UUID")');
    const genKey = await page.inputValue('input[placeholder="e.g. order-ack-40918"]');
    expect(genKey).toBeTruthy();

    // Submit dispatch
    await page.click('button[type="submit"]:has-text("Send Notification")');

    // 6. Verify Automatic Redirect to Detail Page (/app/notifications/:id)
    await expect(page).toHaveURL(/\/app\/notifications\/[a-f0-9-]+/, { timeout: 15_000 });

    // 7. Inspect Full LifecycleRail & Message Sandboxed Iframe
    await expect(page.locator('text=The Dispatch Line')).toBeVisible();
    await expect(page.locator('ol.lifecycle-rail-track')).toBeVisible();

    // Check sandboxed iframe exists with bare sandbox=""
    const iframe = page.locator('iframe[title="Message Content Preview"]');
    await expect(iframe).toBeVisible();
    const sandboxAttr = await iframe.getAttribute('sandbox');
    expect(sandboxAttr).toBe('');

    // Verify Metadata Sidebar Panel
    await expect(page.locator('text=METADATA & IDENTIFIERS')).toBeVisible();
    await expect(page.locator(`text=${recipient}`)).toBeVisible();

    // Verify Immutable Audit Trail
    await expect(page.locator('text=IMMUTABLE AUDIT TRAIL')).toBeVisible();
    const historyRow = page.locator('tr', { hasText: 'QUEUED' });
    await expect(historyRow).toBeVisible();
    await expect(historyRow.locator('text=SYSTEM')).toBeVisible();

    // 8. Cancel from Detail Page
    await page.click('button:has-text("Cancel Notification")');
    const cancelModal = page.locator('.panel', { has: page.locator('h3:has-text("Confirm Cancellation")') });
    await expect(cancelModal).toBeVisible();

    await cancelModal.locator('input[placeholder="e.g. User opted out"]').fill('Customer requested cancellation');
    await cancelModal.locator('button:has-text("Confirm Cancel")').click();
    await expect(cancelModal).not.toBeVisible();

    // Verify Status updated to CANCELLED in detail
    await expect(page.locator('.status-chip:has-text("CANCELLED")').first()).toBeVisible();

    // Verify Audit Trail reflects transition by TENANT
    const cancelledRow = page.locator('tr', { hasText: 'CANCELLED' });
    await expect(cancelledRow).toBeVisible();
    await expect(cancelledRow.locator('text=TENANT')).toBeVisible();
    await expect(cancelledRow.locator('text=Customer requested cancellation')).toBeVisible();

    // 9. Return to Notifications List
    await page.click('a:has-text("Notifications List")');
    await expect(page).toHaveURL(/\/app\/notifications/);

    // 10. Verify Dense Table shows compact LifecycleRail & CANCELLED status
    const tableRow = page.locator('tr', { hasText: recipient });
    await expect(tableRow).toBeVisible();
    await expect(tableRow.locator('.compact-rail-container')).toBeVisible();
    await expect(tableRow.locator('.status-chip')).toHaveText('CANCELLED');

    // 11. Test URL-synced FilterBar
    // Search by recipient query
    await page.fill('input[placeholder="Search recipient or subject…"]', recipient);
    await expect(page).toHaveURL(new RegExp(`search=${encodeURIComponent(recipient)}`));
    await expect(tableRow).toBeVisible();

    // Clear filters
    await page.click('button:has-text("Clear filters")');
    await expect(page.locator('input[placeholder="Search recipient or subject…"]')).toHaveValue('');
  });
});
