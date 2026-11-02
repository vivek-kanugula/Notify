import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  silentRefresh,
  setAccessToken,
  getAccessToken,
  isTokenValid,
  parseJwtPayload,
  _resetClientForTesting
} from '../src/api/client.ts';

function createMockJwt(expSecondsFromNow: number): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const exp = Math.floor(Date.now() / 1000) + expSecondsFromNow;
  const payload = Buffer.from(JSON.stringify({ sub: 'user-123', exp })).toString('base64url');
  const signature = 'mock-signature';
  return `${header}.${payload}.${signature}`;
}

describe('client.ts cross-tab coordination, Web Locks, and token expiry validation', { concurrency: 1 }, () => {
  const originalFetch = globalThis.fetch;
  const originalNavigatorDesc = Object.getOwnPropertyDescriptor(globalThis, 'navigator');

  let httpRefreshCallCount = 0;

  function setNavigatorMock(val: any) {
    Object.defineProperty(globalThis, 'navigator', {
      value: val,
      configurable: true,
      writable: true,
    });
  }

  beforeEach(() => {
    httpRefreshCallCount = 0;

    // Default mock fetch responding to /auth/refresh with a fresh 15-minute access token
    globalThis.fetch = async (url: any) => {
      const urlStr = String(url);
      if (urlStr.includes('/auth/refresh')) {
        httpRefreshCallCount++;
        return {
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: createMockJwt(900), // Valid for 15 minutes
          }),
        } as any;
      }
      return { ok: true, status: 200, json: async () => ({}) } as any;
    };

    _resetClientForTesting();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    if (originalNavigatorDesc) {
      Object.defineProperty(globalThis, 'navigator', originalNavigatorDesc);
    }
    _resetClientForTesting();
  });

  describe('JWT Expiry Validation (isTokenValid)', () => {
    test('returns true for a token expiring in the future', () => {
      const validToken = createMockJwt(300);
      assert.strictEqual(isTokenValid(validToken), true);
    });

    test('returns false for an expired token', () => {
      const expiredToken = createMockJwt(-60);
      assert.strictEqual(isTokenValid(expiredToken), false);
    });

    test('returns false for a token expiring within the buffer window (e.g. 5 seconds)', () => {
      const nearExpiryToken = createMockJwt(5); // Default buffer is 10s
      assert.strictEqual(isTokenValid(nearExpiryToken), false);
    });

    test('returns false for null or malformed tokens', () => {
      assert.strictEqual(isTokenValid(null), false);
      assert.strictEqual(isTokenValid('not.a.valid.jwt'), false);
      assert.strictEqual(isTokenValid('invalid-string'), false);
    });
  });

  describe('Web Locks and Simultaneous Tabs Coordination', () => {
    test('Primary Web Locks: serializes simultaneous refresh calls and deduplicates in-flight calls', async () => {
      let lockAcquiredCount = 0;
      setNavigatorMock({
        locks: {
          request: async (_name: string, callback: () => Promise<any>) => {
            lockAcquiredCount++;
            return await callback();
          },
        },
      });

      const [res1, res2] = await Promise.all([silentRefresh(), silentRefresh()]);

      assert.strictEqual(res1, true);
      assert.strictEqual(res2, true);
      assert.strictEqual(httpRefreshCallCount, 1, 'Only one HTTP refresh should be executed across tabs');
      assert.strictEqual(lockAcquiredCount, 1);
      assert.ok(isTokenValid(getAccessToken()));
    });

    test('Fallback when Web Locks is UNAVAILABLE: disables uncoordinated automatic refresh to protect family reuse', async () => {
      setNavigatorMock({ locks: undefined });

      const refreshed = await silentRefresh();

      assert.strictEqual(refreshed, false, 'Should cleanly return false without making uncoordinated HTTP request');
      assert.strictEqual(httpRefreshCallCount, 0, 'No HTTP refresh should be fired when Web Locks is unavailable');
      assert.strictEqual(getAccessToken(), null, 'Access token remains null, directing user to clean re-login');
    });
  });

  describe('Cross-Tab Token Sharing & Expiry Handoff', { concurrency: 1 }, () => {
    test('Valid token handoff: secondary tab receives active token from sibling and skips HTTP refresh', async () => {
      // Simulate Tab 1 having a valid active token
      const validToken = createMockJwt(600);
      setAccessToken(validToken);

      // Simulate Tab 2 querying via BroadcastChannel
      const tab2Channel = new BroadcastChannel('notify_auth_tab_sync');
      let receivedToken: string | null = null;
      tab2Channel.onmessage = (event) => {
        if (event.data?.type === 'REFRESH_SUCCESS') {
          receivedToken = event.data.token;
        }
      };

      tab2Channel.postMessage({ type: 'REQUEST_TOKEN', senderId: 'tab-2' });

      for (let i = 0; i < 20 && !receivedToken; i++) {
        await new Promise((r) => setTimeout(r, 20));
      }
      tab2Channel.close();

      assert.strictEqual(receivedToken, validToken);
      assert.strictEqual(httpRefreshCallCount, 0, 'No HTTP request needed when sibling tab shares valid token');
    });

    test('Expired token handoff rejection: Tab 1 refuses to hand over an expired token', async () => {
      // Manually set an expired token on Tab 1
      const expiredToken = createMockJwt(-120);
      setAccessToken(expiredToken); // Should be rejected immediately by setAccessToken

      assert.strictEqual(getAccessToken(), null, 'Expired token cannot be set as active access token');

      const tab2Channel = new BroadcastChannel('notify_auth_tab_sync');
      let receivedToken: string | null = null;
      tab2Channel.onmessage = (event) => {
        if (event.data?.type === 'REFRESH_SUCCESS') {
          receivedToken = event.data.token;
        }
      };

      tab2Channel.postMessage({ type: 'REQUEST_TOKEN', senderId: 'tab-2' });

      await new Promise((r) => setTimeout(r, 60));
      tab2Channel.close();

      assert.strictEqual(receivedToken, null, 'Expired token must not be broadcast to sibling tabs');
    });

    test('Expired broadcast rejection: Tab 2 ignores incoming broadcast of an expired token', async () => {
      const expiredToken = createMockJwt(-300);

      // Directly broadcast an expired token as if sent by a buggy/malicious tab
      const externalChannel = new BroadcastChannel('notify_auth_tab_sync');
      externalChannel.postMessage({ type: 'REFRESH_SUCCESS', token: expiredToken });

      await new Promise((r) => setTimeout(r, 60));
      externalChannel.close();

      // Tab 2 must NOT accept the expired token as fresh
      assert.strictEqual(getAccessToken(), null, 'Expired broadcasted token must be rejected');
      assert.strictEqual(isTokenValid(getAccessToken()), false);
    });

    test('Unsolicited broadcast rejection when no identity installed: Tab 2 (installedSub === null) ignores REFRESH_SUCCESS', async () => {
      // Tab 2 has no installed identity (e.g. after receiving LOGOUT or before sign-in)
      assert.strictEqual(getAccessToken(), null);

      const validToken = createMockJwt(900);
      const externalChannel = new BroadcastChannel('notify_auth_tab_sync');
      externalChannel.postMessage({ type: 'REFRESH_SUCCESS', token: validToken });

      await new Promise((r) => setTimeout(r, 60));
      externalChannel.close();

      // Tab 2 must NOT accept unsolicited broadcast token without an existing matching identity
      assert.strictEqual(getAccessToken(), null, 'Unsolicited broadcast token must be ignored when no identity is installed');
    });
  });
});
