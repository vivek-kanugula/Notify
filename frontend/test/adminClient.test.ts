import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  setAdminAccessToken,
  getAdminAccessToken,
  isAdminTokenValid,
  parseJwtPayload,
  adminSilentRefresh,
  adminFetch,
  clearAdminSession,
  _resetAdminClientForTesting,
} from '../src/api/adminClient.ts';

function createMockAdminJwt(expSecondsFromNow: number, aud = 'notify-admin', sub = 'admin-123'): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const exp = Math.floor(Date.now() / 1000) + expSecondsFromNow;
  const payload = Buffer.from(JSON.stringify({ sub, aud, exp, roles: ['ROLE_PLATFORM_ADMIN'] })).toString('base64url');
  const signature = 'mock-signature';
  return `${header}.${payload}.${signature}`;
}

describe('adminClient.ts token validation, audience isolation, and silent refresh', { concurrency: 1 }, () => {
  const originalFetch = globalThis.fetch;
  let httpRefreshCallCount = 0;

  beforeEach(() => {
    httpRefreshCallCount = 0;
    _resetAdminClientForTesting();

    globalThis.fetch = async (url: any) => {
      const urlStr = String(url);
      if (urlStr.includes('/auth/admin/refresh')) {
        httpRefreshCallCount++;
        return {
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: createMockAdminJwt(900),
          }),
        } as any;
      }
      return { ok: true, status: 200, json: async () => ({}) } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetAdminClientForTesting();
  });

  test('parseJwtPayload parses admin token and extracts claims', () => {
    const token = createMockAdminJwt(300, 'notify-admin', 'admin-xyz');
    const payload = parseJwtPayload(token);
    assert.ok(payload);
    assert.strictEqual(payload?.sub, 'admin-xyz');
    assert.strictEqual(payload?.aud, 'notify-admin');
    assert.deepStrictEqual(payload?.roles, ['ROLE_PLATFORM_ADMIN']);
  });

  test('isAdminTokenValid strictly accepts only tokens with aud: notify-admin', () => {
    const validAdminToken = createMockAdminJwt(300, 'notify-admin');
    const validAdminTokenWithArrayAud = createMockAdminJwt(300, ['notify-admin'] as any);
    const tenantToken = createMockAdminJwt(300, 'notify-api'); // Wrong audience

    assert.strictEqual(isAdminTokenValid(validAdminToken), true);
    assert.strictEqual(isAdminTokenValid(validAdminTokenWithArrayAud), true, 'Array aud from JJWT must be accepted');
    assert.strictEqual(isAdminTokenValid(tenantToken), false, 'Tenant JWT must be rejected by admin client');
  });

  test('isAdminTokenValid rejects expired tokens or tokens within buffer window', () => {
    const validToken = createMockAdminJwt(30);
    const nearlyExpiredToken = createMockAdminJwt(5); // Less than default 10s buffer
    const expiredToken = createMockAdminJwt(-60);

    assert.strictEqual(isAdminTokenValid(validToken), true);
    assert.strictEqual(isAdminTokenValid(nearlyExpiredToken), false);
    assert.strictEqual(isAdminTokenValid(expiredToken), false);
    assert.strictEqual(isAdminTokenValid(null), false);
  });

  test('adminSilentRefresh calls /auth/admin/refresh and updates in-memory token', async () => {
    const success = await adminSilentRefresh();
    assert.strictEqual(success, true);
    assert.strictEqual(httpRefreshCallCount, 1);

    const token = getAdminAccessToken();
    assert.ok(token);
    assert.strictEqual(isAdminTokenValid(token), true);
  });

  test('adminFetch attaches Authorization: Bearer <adminToken> header', async () => {
    const token = createMockAdminJwt(300);
    setAdminAccessToken(token);

    let sentAuthHeader: string | null = null;
    globalThis.fetch = async (_url: any, options: any) => {
      const headers = new Headers(options?.headers);
      sentAuthHeader = headers.get('Authorization');
      return { ok: true, status: 200, json: async () => ({}) } as any;
    };

    await adminFetch('/api/v1/admin/tenants');
    assert.strictEqual(sentAuthHeader, `Bearer ${token}`);
  });

  test('adminFetch retries on 401 via adminSilentRefresh', async () => {
    let callIndex = 0;
    globalThis.fetch = async (url: any) => {
      const urlStr = String(url);
      if (urlStr.includes('/auth/admin/refresh')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: createMockAdminJwt(900),
          }),
        } as any;
      }

      callIndex++;
      if (callIndex === 1) {
        return { ok: false, status: 401 } as any;
      }
      return { ok: true, status: 200, json: async () => ({ recovered: true }) } as any;
    };

    // Initial stale token
    setAdminAccessToken(createMockAdminJwt(300));

    const res = await adminFetch('/api/v1/admin/tenants');
    assert.strictEqual(res.status, 200);
    assert.strictEqual(callIndex, 2);
  });

  test('clearAdminSession wipes admin token and resets refresh state', () => {
    setAdminAccessToken(createMockAdminJwt(300));
    assert.ok(getAdminAccessToken());

    clearAdminSession();
    assert.strictEqual(getAdminAccessToken(), null);
  });
});
