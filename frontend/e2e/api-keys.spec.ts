import { test, expect } from '@playwright/test';

test.describe('API Keys Management E2E Journey', () => {
  test('sign up, create scoped key, verify one-time secret reveal, rotate with 24h overlap, and revoke keys', async ({ page }) => {
    const testId = `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const email = `playwright_${testId}@notify.local`;
    const tenantName = `Playwright Org ${testId}`;
    const password = 'Password123!';
    const keyName = `E2E Dispatcher ${testId}`;

    // 1. Sign up new unique tenant
    await page.goto('/login');
    await expect(page.locator('form.login-form')).toBeVisible();

    // Toggle to signup form
    await page.click('button:has-text("Don\'t have an account? Sign up")');
    await expect(page.locator('#tenantName')).toBeVisible();

    await page.fill('#tenantName', tenantName);
    await page.fill('#email', email);
    await page.fill('#password', password);
    await page.click('button.login-button:has-text("Create Account")');

    // Wait for redirect to dashboard
    await expect(page).toHaveURL(/\/app/, { timeout: 15_000 });
    await expect(page.locator('nav.sidebar-nav')).toBeVisible();

    // 2. Navigate to /app/api-keys
    await page.click('a.nav-item:has-text("API Keys")');
    await expect(page).toHaveURL(/\/app\/api-keys/);
    await expect(page.locator('h1:has-text("API Keys")')).toBeVisible();

    // 3. Create scoped API key
    await page.click('button:has-text("Create API Key")');
    await expect(page.locator('h2:has-text("Generate Scoped API Key")')).toBeVisible();

    await page.fill('input[placeholder="e.g. Backend Dispatcher"]', keyName);
    await page.click('button:has-text("Preset: Send only")');
    await page.click('button:has-text("Generate Key")');

    // 4. Verify one-time secret reveal modal
    const revealModal1 = page.locator('.panel', {
      has: page.locator('h2:has-text("API Key Generated Successfully")'),
    });
    await expect(revealModal1).toBeVisible();
    await expect(revealModal1.locator('text=Copy this key now. You won\'t see it again.')).toBeVisible();

    // Read the revealed secret from modal
    const secretCodeLocator1 = revealModal1.locator('code.mono');
    await expect(secretCodeLocator1).toBeVisible();
    const rawSecret1 = (await secretCodeLocator1.textContent())?.trim();
    expect(rawSecret1).toBeDefined();
    expect(rawSecret1).toMatch(/^ntf_[A-Za-z0-9_-]{40}$/);

    // Dismiss reveal modal
    await revealModal1.locator('button:has-text("I have saved this key")').click();
    await expect(revealModal1).not.toBeVisible();

    // Verify key in table
    const firstRow = page.locator('tr', { hasText: keyName });
    await expect(firstRow).toBeVisible();
    await expect(firstRow.locator('.status-chip')).toHaveText('ACTIVE');
    await expect(firstRow.locator('code.mono')).toContainText('…');

    // Secret must NOT be anywhere in the table
    const tableTextAfterCreate = await page.locator('table').textContent();
    expect(tableTextAfterCreate).not.toContain(rawSecret1!);

    // 5. Rotate key with default 24-hour overlap
    await firstRow.locator('button:has-text("Rotate")').click();
    const rotateModal = page.locator('.panel', {
      has: page.locator('h2:has-text("Rotate API Key")'),
    });
    await expect(rotateModal).toBeVisible();
    await expect(rotateModal.locator('text=The existing key will remain active for 24 hours')).toBeVisible();

    await rotateModal.locator('button:has-text("Rotate Key")').click();

    // Verify new secret in reveal modal
    const revealModal2 = page.locator('.panel', {
      has: page.locator('h2:has-text("API Key Generated Successfully")'),
    });
    await expect(revealModal2).toBeVisible();
    const secretCodeLocator2 = revealModal2.locator('code.mono');
    await expect(secretCodeLocator2).toBeVisible();
    const rawSecret2 = (await secretCodeLocator2.textContent())?.trim();
    expect(rawSecret2).toBeDefined();
    expect(rawSecret2).toMatch(/^ntf_[A-Za-z0-9_-]{40}$/);
    expect(rawSecret2).not.toEqual(rawSecret1);

    // Dismiss second reveal modal
    await revealModal2.locator('button:has-text("I have saved this key")').click();
    await expect(revealModal2).not.toBeVisible();

    // Verify both rows in table
    const rotatingRow = page.locator('tr', { hasText: 'ROTATING' });
    await expect(rotatingRow).toBeVisible();
    // Old key in grace period must have Rotate disabled
    await expect(rotatingRow.locator('button:has-text("Rotate")')).toBeDisabled();

    const activeRow = page.locator('tr', { hasText: 'ACTIVE' });
    await expect(activeRow).toBeVisible();
    await expect(activeRow.locator('button:has-text("Rotate")')).toBeEnabled();

    // Neither secret in table
    const tableTextAfterRotate = await page.locator('table').textContent();
    expect(tableTextAfterRotate).not.toContain(rawSecret1!);
    expect(tableTextAfterRotate).not.toContain(rawSecret2!);

    // 6. Revoke the active key
    await activeRow.locator('button:has-text("Revoke")').click();
    const revokeModal = page.locator('.panel', {
      has: page.locator('h2:has-text("Revoke API Key")'),
    });
    await expect(revokeModal).toBeVisible();
    await revokeModal.locator('button:has-text("Yes, Revoke Key")').click();
    await expect(revokeModal).not.toBeVisible();

    // 7. Revoke the rotating key
    const remainingRotatingRow = page.locator('tr', { hasText: 'ROTATING' });
    await remainingRotatingRow.locator('button:has-text("Revoke")').click();
    await expect(revokeModal).toBeVisible();
    await revokeModal.locator('button:has-text("Yes, Revoke Key")').click();
    await expect(revokeModal).not.toBeVisible();

    // 8. Verify both keys are now REVOKED
    await expect(page.locator('.status-chip:has-text("REVOKED")')).toHaveCount(2);
  });
});
