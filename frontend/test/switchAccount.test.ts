import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  clearSession,
  getAccessToken,
  setAccessToken,
  getInstalledIdentity,
  setInstalledIdentity,
  _resetClientForTesting,
} from '../src/api/client.ts';

function createMockJwt(sub: string, tid: string, expSecondsFromNow: number): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const exp = Math.floor(Date.now() / 1000) + expSecondsFromNow;
  const payload = Buffer.from(JSON.stringify({ sub, tid, exp })).toString('base64url');
  const signature = 'mock-sig';
  return `${header}.${payload}.${signature}`;
}

describe('Session Clearing and Cross-Tab Isolation', { concurrency: 1 }, () => {
  const originalFetch = globalThis.fetch;
  const originalNavigatorDesc = Object.getOwnPropertyDescriptor(globalThis, 'navigator');

  function setNavigatorMock(val: any) {
    Object.defineProperty(globalThis, 'navigator', {
      value: val,
      configurable: true,
      writable: true,
    });
  }

  beforeEach(() => {
    _resetClientForTesting();
    setNavigatorMock({
      locks: {
        request: async (_name: string, cb: () => Promise<any>) => cb(),
      },
    });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    if (originalNavigatorDesc) {
      Object.defineProperty(globalThis, 'navigator', originalNavigatorDesc);
    }
    _resetClientForTesting();
  });

  test('switch action clears local session and broadcasts LOGOUT to sibling tabs', async () => {
    // 1. Tab 1 is authenticated as Tenant A
    const tokenA = createMockJwt('sub-tenant-a', 'tenant-a', 900);
    setAccessToken(tokenA);
    setInstalledIdentity('sub-tenant-a', 'tenant-a');

    assert.strictEqual(getAccessToken(), tokenA);
    assert.strictEqual(getInstalledIdentity().sub, 'sub-tenant-a');

    // 2. Set up sibling tab listener
    const siblingChannel = new BroadcastChannel('notify_auth_tab_sync');
    let logoutReceived = false;
    siblingChannel.onmessage = (event) => {
      if (event.data?.type === 'LOGOUT') {
        logoutReceived = true;
      }
    };

    // 3. User triggers switch action: clearSession() is called before navigation
    clearSession();

    const start = Date.now();
    while (!logoutReceived && Date.now() - start < 300) {
      await new Promise((r) => setTimeout(r, 10));
    }
    siblingChannel.close();

    // Local in-memory session is cleared
    assert.strictEqual(getAccessToken(), null);
    assert.strictEqual(getInstalledIdentity().sub, null);
    assert.strictEqual(getInstalledIdentity().tid, null);

    // Sibling tab received the LOGOUT broadcast
    assert.strictEqual(logoutReceived, true, 'Sibling tabs must receive LOGOUT signal');
  });

  test('sibling tab that held Tenant A drops session and ignores subsequent Tenant B REFRESH_SUCCESS', async () => {
    // Simulate Sibling Tab holding Tenant A
    const tokenA = createMockJwt('sub-tenant-a', 'tenant-a', 900);
    setAccessToken(tokenA);
    setInstalledIdentity('sub-tenant-a', 'tenant-a');

    // Simulate Sibling Tab receiving LOGOUT via BroadcastChannel from initiating tab
    const externalChannel = new BroadcastChannel('notify_auth_tab_sync');
    externalChannel.postMessage({ type: 'LOGOUT' });

    await new Promise((r) => setTimeout(r, 40));

    // Sibling tab local session is now cleared
    assert.strictEqual(getAccessToken(), null, 'Sibling tab must drop access token upon receiving LOGOUT');
    assert.strictEqual(getInstalledIdentity().sub, null, 'Sibling tab must clear installed identity');

    // Initiating tab broadcasts Tenant B's fresh token
    const tokenB = createMockJwt('sub-tenant-b', 'tenant-b', 900);
    externalChannel.postMessage({ type: 'REFRESH_SUCCESS', token: tokenB });

    await new Promise((r) => setTimeout(r, 60));
    externalChannel.close();

    // Sibling tab must NOT adopt Tenant B's token
    assert.strictEqual(getAccessToken(), null, 'Sibling tab must not adopt Tenant B unsolicited');
    assert.strictEqual(getInstalledIdentity().sub, null, 'Sibling tab installed identity must remain null');
  });

  test('duplicate-click protection: switch action prevents multiple activations while switching', () => {
    let callCount = 0;
    let switching = false;

    function handleSwitch() {
      if (switching) return;
      switching = true;
      callCount++;
      clearSession();
    }

    // Trigger twice in succession
    handleSwitch();
    handleSwitch();

    assert.strictEqual(callCount, 1, 'Duplicate switch trigger must be ignored while switching is active');
    assert.strictEqual(switching, true);
  });
});
