import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import { listAdminAuditLog } from '../src/api/adminAudit.ts';
import { _resetAdminClientForTesting } from '../src/api/adminClient.ts';

describe('adminAudit.ts parameter formatting and query execution', () => {
  const originalFetch = globalThis.fetch;
  let lastRequestUrl: string | null = null;

  beforeEach(() => {
    lastRequestUrl = null;
    _resetAdminClientForTesting();

    globalThis.fetch = async (url: any) => {
      lastRequestUrl = String(url);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          content: [
            {
              id: 101,
              adminId: '99999999-9999-9999-9999-999999999999',
              adminEmail: 'secops@notify.dev',
              action: 'TENANT_SUSPENDED',
              targetType: 'TENANT',
              targetId: '44444444-4444-4444-4444-444444444444',
              tenantId: '44444444-4444-4444-4444-444444444444',
              tenantSlug: 'rogue-app',
              reason: 'Rate limit abuse detected',
              details: JSON.stringify({ rateLimitExceeded: 500 }),
              ipAddress: '192.168.1.100',
              createdAt: '2026-10-27T11:00:00Z',
            },
          ],
          totalElements: 1,
          totalPages: 1,
          size: 20,
          number: 0,
        }),
      } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetAdminClientForTesting();
  });

  test('listAdminAuditLog serializes action, tenantId, and pagination parameters', async () => {
    const res = await listAdminAuditLog({
      page: 0,
      size: 25,
      tenantId: '44444444-4444-4444-4444-444444444444',
      action: 'TENANT_SUSPENDED',
      targetType: 'TENANT',
      from: '2026-10-20T00:00:00Z',
      to: '2026-10-27T00:00:00Z',
    });

    assert.ok(lastRequestUrl);
    const parsedUrl = new URL(lastRequestUrl, 'http://localhost');
    assert.strictEqual(parsedUrl.searchParams.get('page'), '0');
    assert.strictEqual(parsedUrl.searchParams.get('size'), '25');
    assert.strictEqual(parsedUrl.searchParams.get('tenantId'), '44444444-4444-4444-4444-444444444444');
    assert.strictEqual(parsedUrl.searchParams.get('action'), 'TENANT_SUSPENDED');
    assert.strictEqual(parsedUrl.searchParams.get('targetType'), 'TENANT');
    assert.strictEqual(parsedUrl.searchParams.get('from'), '2026-10-20T00:00:00Z');
    assert.strictEqual(parsedUrl.searchParams.get('to'), '2026-10-27T00:00:00Z');

    assert.strictEqual(res.content.length, 1);
    assert.strictEqual(res.content[0].action, 'TENANT_SUSPENDED');
    assert.strictEqual(res.content[0].tenantSlug, 'rogue-app');
  });
});
