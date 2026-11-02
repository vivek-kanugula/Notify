import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  listAdminTenants,
  getAdminTenant,
  updateAdminTenantStatus,
  deleteAdminTenant,
} from '../src/api/adminTenants.ts';
import { _resetAdminClientForTesting } from '../src/api/adminClient.ts';

describe('adminTenants.ts API request serialization & contract enforcement', () => {
  const originalFetch = globalThis.fetch;
  let lastRequest: { url: string; method?: string; headers: Headers; body?: string } | null = null;

  beforeEach(() => {
    lastRequest = null;
    _resetAdminClientForTesting();

    globalThis.fetch = async (url: any, options: any) => {
      lastRequest = {
        url: String(url),
        method: options?.method,
        headers: new Headers(options?.headers),
        body: options?.body,
      };

      // Mock responses
      if (lastRequest.url.startsWith('/api/v1/admin/tenants') && options?.method === 'DELETE') {
        return { ok: true, status: 204 } as any;
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({
          content: [],
          totalElements: 0,
          totalPages: 0,
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

  test('listAdminTenants serializes query, status, and pagination parameters', async () => {
    await listAdminTenants({
      page: 2,
      size: 15,
      status: 'SUSPENDED',
      query: 'acme-corp',
    });

    assert.ok(lastRequest);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.searchParams.get('page'), '2');
    assert.strictEqual(parsedUrl.searchParams.get('size'), '15');
    assert.strictEqual(parsedUrl.searchParams.get('status'), 'SUSPENDED');
    assert.strictEqual(parsedUrl.searchParams.get('query'), 'acme-corp');
  });

  test('getAdminTenant queries single tenant resource path', async () => {
    const tenantId = '77777777-7777-7777-7777-777777777777';
    await getAdminTenant(tenantId);

    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.url, `/api/v1/admin/tenants/${tenantId}`);
  });

  test('updateAdminTenantStatus sends PATCH with status and reason body', async () => {
    const tenantId = '88888888-8888-8888-8888-888888888888';
    await updateAdminTenantStatus(tenantId, {
      status: 'SUSPENDED',
      reason: 'Terms violation: spam volume spike',
    });

    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.method, 'PATCH');
    assert.strictEqual(lastRequest.url, `/api/v1/admin/tenants/${tenantId}/status`);
    assert.ok(lastRequest.body);
    const parsedBody = JSON.parse(lastRequest.body);
    assert.strictEqual(parsedBody.status, 'SUSPENDED');
    assert.strictEqual(parsedBody.reason, 'Terms violation: spam volume spike');
  });

  test('deleteAdminTenant attaches mandatory X-Confirm-Tenant-Slug header', async () => {
    const tenantId = '99999999-9999-9999-9999-999999999999';
    const slug = 'acme-production';
    const reason = 'Requested data erasure';

    await deleteAdminTenant(tenantId, slug, reason);

    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.method, 'DELETE');
    assert.strictEqual(lastRequest.headers.get('X-Confirm-Tenant-Slug'), slug);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.searchParams.get('reason'), reason);
  });
});
