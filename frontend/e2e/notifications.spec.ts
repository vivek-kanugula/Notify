import { test, expect } from '@playwright/test';

test.describe('Notifications Ingress & Lifecycle E2E Journey', () => {
  test('sign up, dispatch notification, verify idempotent replay, inspect lifecycle audit trail, and cancel', async ({ page }) => {
    const testId = `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const email = `playwright_notif_${testId}@notify.local`;
    const tenantName = `Notifications Org ${testId}`;
    const password = 'Password123!';
    const idempotencyKey = `idem_${testId}`;
    const recipient = `customer_${testId}@example.com`;
    const subject = `Order ${testId} Receipt`;
    const bodyContent = `Thank you for your order! Confirmation reference #${testId}.`;

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
    await expect(page.locator('nav.sidebar-nav')).toBeVisible();

    // 3. Navigate to Notifications page
    await page.click('a.nav-item:has-text("Notifications")');
    await expect(page).toHaveURL(/\/app\/notifications/);
    await expect(page.locator('h1:has-text("Notifications")')).toBeVisible();

    // 4. Verify Empty State
    await expect(page.locator('text=No notifications found')).toBeVisible();

    // 5. Open Dispatch Notification Modal
    await page.click('button:has-text("Send Notification")');
    const modal = page.locator('.panel', { has: page.locator('h2:has-text("Dispatch Notification")') });
    await expect(modal).toBeVisible();

    // Fill form fields
    await modal.locator('input[placeholder="user@example.com"]').fill(recipient);
    await modal.locator('input[placeholder="Notification Subject"]').fill(subject);
    await modal.locator('textarea[placeholder="Notification content / payload..."]').fill(bodyContent);
    await modal.locator('input[placeholder="e.g. order-ack-40918"]').fill(idempotencyKey);

    // Submit dispatch
    await modal.locator('button:has-text("Accept & Enqueue")').click();

    // Verify acceptance feedback
    await expect(modal.locator('text=Success: Accepted notification')).toBeVisible();
    await expect(modal.locator('text=(QUEUED)')).toBeVisible();

    // Close modal
    await modal.locator('button:has-text("✕")').click();
    await expect(modal).not.toBeVisible();

    // 6. Verify Truth-in-Advertising Banner and Notification in table
    await expect(page.locator('text=Transactional outbox rows are committed for QUEUED notifications')).toBeVisible();

    const row = page.locator('tr', { hasText: recipient });
    await expect(row).toBeVisible();
    await expect(row.locator('.status-chip')).toHaveText('QUEUED');
    await expect(row.locator('text=EMAIL')).toBeVisible();

    // 7. Verify Idempotent Replay (Matching Replay 202)
    await page.click('button:has-text("Send Notification")');
    await expect(modal).toBeVisible();

    await modal.locator('input[placeholder="user@example.com"]').fill(recipient);
    await modal.locator('input[placeholder="Notification Subject"]').fill(subject);
    await modal.locator('textarea[placeholder="Notification content / payload..."]').fill(bodyContent);
    await modal.locator('input[placeholder="e.g. order-ack-40918"]').fill(idempotencyKey);

    await modal.locator('button:has-text("Accept & Enqueue")').click();

    // Must show matching idempotent replay indicator
    await expect(modal.locator('text=Matching Idempotent Replay (202):')).toBeVisible();
    await expect(modal.locator('text=(QUEUED)')).toBeVisible();

    await modal.locator('button:has-text("✕")').click();
    await expect(modal).not.toBeVisible();

    // 8. Open Detail Drawer & Verify Lifecycle Rail and Immutable Audit Trail
    await row.locator('button:has-text("Detail")').click();
    const detailPanel = page.locator('.panel', { has: page.locator('text=IMMUTABLE AUDIT TRAIL') });
    await expect(detailPanel).toBeVisible();

    // Verify recipient and body preview
    await expect(detailPanel.locator(`text=${recipient}`)).toBeVisible();
    await expect(detailPanel.locator(`text=${bodyContent}`)).toBeVisible();

    // Verify audit trail table has initial CREATED -> QUEUED transition by SYSTEM
    await expect(detailPanel.locator('text=IMMUTABLE AUDIT TRAIL')).toBeVisible();
    const historyRow = detailPanel.locator('tr', { hasText: 'QUEUED' });
    await expect(historyRow).toBeVisible();
    await expect(historyRow.locator('text=SYSTEM')).toBeVisible();

    // 9. Perform Cancellation
    await detailPanel.locator('button:has-text("Cancel Notification")').click();
    const cancelModal = page.locator('.panel', { has: page.locator('h3:has-text("Confirm Cancellation")') });
    await expect(cancelModal).toBeVisible();

    await cancelModal.locator('input[placeholder="e.g. User opted out"]').fill('E2E cancelled test');
    await cancelModal.locator('button:has-text("Confirm Cancel")').click();
    await expect(cancelModal).not.toBeVisible();

    // Verify status updated to CANCELLED in detail panel
    await expect(detailPanel.locator('.status-chip:has-text("CANCELLED")').first()).toBeVisible();

    // Verify audit trail now has CANCELLED transition by TENANT
    const cancelledHistoryRow = detailPanel.locator('tr', { hasText: 'CANCELLED' });
    await expect(cancelledHistoryRow).toBeVisible();
    await expect(cancelledHistoryRow.locator('text=TENANT')).toBeVisible();
    await expect(cancelledHistoryRow.locator('text=E2E cancelled test')).toBeVisible();

    // Close detail drawer
    await detailPanel.locator('button:has-text("✕")').click();
    await expect(detailPanel).not.toBeVisible();

    // 10. Verify table now reflects CANCELLED status
    await expect(row.locator('.status-chip')).toHaveText('CANCELLED');
  });
});
