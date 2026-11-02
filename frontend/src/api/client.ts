/**
 * API client with automatic 401 → silent refresh → retry.
 * Access token kept in memory only (never localStorage/cookies).
 * Coordinates silent refresh across browser tabs using Web Locks / BroadcastChannel.
 */

let accessToken: string | null = null;
let refreshPromise: Promise<boolean> | null = null;
let lastRefreshTime = 0;

/**
 * The sub+tid of the identity currently installed in this tab.
 */
let installedSub: string | null = null;
let installedTid: string | null = null;

type AuthChangeListener = (token: string | null) => void;
const authListeners = new Set<AuthChangeListener>();

export function onAuthChange(listener: AuthChangeListener): () => void {
  authListeners.add(listener);
  return () => {
    authListeners.delete(listener);
  };
}

// In-memory tab synchronization channel (never touches storage/disk)
const authChannel: BroadcastChannel | null =
  typeof BroadcastChannel !== 'undefined'
    ? new BroadcastChannel('notify_auth_tab_sync')
    : null;

if (authChannel && typeof (authChannel as any).unref === 'function') {
  (authChannel as any).unref();
}

function getTabId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

const currentTabId = getTabId();

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Parses JWT payload without external libraries.
 */
export function parseJwtPayload(token: string): { exp?: number; [key: string]: any } | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const base64Url = parts[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload =
      typeof atob === 'function'
        ? atob(base64)
        : typeof (globalThis as any).Buffer !== 'undefined'
          ? (globalThis as any).Buffer.from(base64, 'base64').toString('utf-8')
          : null;
    if (!jsonPayload) return null;
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

/**
 * Validates a JWT against its actual expiration claim (`exp`).
 * Prevents expired or nearly-expired tokens from being accepted or shared.
 */
export function isTokenValid(token: string | null, bufferSeconds = 10): boolean {
  if (!token) return false;
  const payload = parseJwtPayload(token);
  if (!payload || typeof payload.exp !== 'number') return false;
  const nowSeconds = Math.floor(Date.now() / 1000);
  return payload.exp > nowSeconds + bufferSeconds;
}

if (authChannel) {
  authChannel.onmessage = (event: MessageEvent) => {
    if (event.data?.type === 'REFRESH_SUCCESS' && typeof event.data.token === 'string') {
      const incomingToken = event.data.token;
      // Validate incoming token by actual JWT expiration before accepting
      if (!isTokenValid(incomingToken)) return;

      // Identity rule (plan 3.3a): a REFRESH_SUCCESS broadcast from a sibling tab is accepted
      // only when sub matches the identity already installed in this tab, or when this tab
      // holds no identity yet. A mismatching sub means a different account refreshed — ignore.
      // Defect 4: reject a REFRESH_SUCCESS token whose sub is absent, as the plan states.
      const incomingPayload = parseJwtPayload(incomingToken);
      const incomingSub = incomingPayload?.sub as string | undefined;
      if (!installedSub || incomingSub !== installedSub) {
        // Different identity or no identity installed: do not install unsolicited broadcast token.
        // A sibling tab that received LOGOUT stays logged out and cannot be silently switched.
        return;
      }

      accessToken = incomingToken;
      lastRefreshTime = Date.now();
      authListeners.forEach((fn) => fn(accessToken));
    } else if (event.data?.type === 'REQUEST_TOKEN') {
      // Validate our token expiry before handing it to sibling tab
      if (isTokenValid(accessToken)) {
        authChannel.postMessage({ type: 'REFRESH_SUCCESS', token: accessToken });
      }
    } else if (event.data?.type === 'LOGOUT') {
      accessToken = null;
      installedSub = null;
      installedTid = null;
      lastRefreshTime = 0;
      authListeners.forEach((fn) => fn(null));
    }
  };
}

export function setAccessToken(token: string | null) {
  if (token && !isTokenValid(token)) {
    accessToken = null;
    installedSub = null;
    installedTid = null;
    lastRefreshTime = 0;
    authChannel?.postMessage({ type: 'LOGOUT' });
    authListeners.forEach((fn) => fn(null));
    return;
  }

  accessToken = token;
  if (token) {
    lastRefreshTime = Date.now();
    const payload = parseJwtPayload(token);
    if (!installedSub && payload?.sub) {
      installedSub = payload.sub as string;
      installedTid = (payload.tid as string | undefined) ?? null;
    }
    authChannel?.postMessage({ type: 'REFRESH_SUCCESS', token });
  } else {
    installedSub = null;
    installedTid = null;
    lastRefreshTime = 0;
    authChannel?.postMessage({ type: 'LOGOUT' });
  }
  authListeners.forEach((fn) => fn(token));
}

export function getAccessToken(): string | null {
  return accessToken;
}

async function doHttpRefresh(): Promise<boolean> {
  try {
    const res = await fetch('/auth/refresh', {
      method: 'POST',
      credentials: 'include', // Send HttpOnly refresh cookie
    });
    if (res.ok) {
      const data = await res.json();
      const payload = parseJwtPayload(data.accessToken);
      const sub = payload?.sub as string | undefined;
      const tid = payload?.tid as string | undefined;

      // Identity rule (plan 3.3a): only callback bootstrap may change an already installed identity.
      // If an ordinary refresh returns a token for a different sub, reject it (clear session, re-login).
      if (installedSub && sub && sub !== installedSub) {
        clearSession();
        return false;
      }

      setAccessToken(data.accessToken);
      if (!installedSub && sub) {
        setInstalledIdentity(sub, tid ?? null);
      }
      return true;
    }
    setAccessToken(null);
    return false;
  } catch {
    setAccessToken(null);
    return false;
  }
}

/**
 * Coordinated silent refresh across tabs:
 * 1. Deduplicates in-flight calls within the current tab.
 * 2. Uses in-memory BroadcastChannel to query sibling tabs for an existing valid token.
 *    Only valid tokens (verified by exp claim) are handed over or accepted.
 * 3. Primary coordinator: Web Locks API (navigator.locks) serializes cross-tab refresh.
 * 4. Fallback when Web Locks is unavailable: To prevent uncoordinated concurrent tabs
 *    from sending the same refresh cookie and triggering server-side family revocation,
 *    uncoordinated automatic HTTP refresh is disabled, providing a clean re-login recovery path.
 */
export async function silentRefresh(): Promise<boolean> {
  if (refreshPromise) return refreshPromise;

  // If another tab refreshed very recently and our token is still valid
  if (isTokenValid(accessToken) && Date.now() - lastRefreshTime < 10000) {
    return true;
  }

  const executeRefresh = async (): Promise<boolean> => {
    // 1. Optimization: query sibling tabs over in-memory BroadcastChannel first
    if (!isTokenValid(accessToken) && authChannel) {
      authChannel.postMessage({ type: 'REQUEST_TOKEN', senderId: currentTabId });
      await sleep(50);
      if (isTokenValid(accessToken)) {
        return true;
      }
    }

    // 2. Primary coordinator: Web Locks API
    if (typeof navigator !== 'undefined' && navigator.locks) {
      return navigator.locks.request('notify_refresh_tab_lock', async () => {
        // Re-check after acquiring the lock in case a sibling tab completed refresh while we waited
        if (isTokenValid(accessToken)) {
          return true;
        }
        return await doHttpRefresh();
      });
    }

    // 3. Fallback when Web Locks is UNAVAILABLE:
    // Do NOT perform uncoordinated automatic HTTP refresh across tabs. Concurrent
    // uncoordinated requests with the same refresh cookie risk triggering server-side
    // family reuse revocation. Return false to provide a clean re-login recovery path.
    return false;
  };

  refreshPromise = executeRefresh().finally(() => {
    refreshPromise = null;
  });

  return refreshPromise;
}

/**
 * Clears the in-memory access token and broadcasts LOGOUT to sibling tabs.
 * Used at the start of a callback bootstrap sequence to prevent the old token
 * from being re-used while the new identity is being established.
 */
export function clearSession(): void {
  accessToken = null;
  installedSub = null;
  installedTid = null;
  lastRefreshTime = 0;
  authChannel?.postMessage({ type: 'LOGOUT' });
  authListeners.forEach((fn) => fn(null));
}

/**
 * Records the identity installed in this tab after a successful bootstrap or password login.
 * Must be called whenever setAccessToken is called with a valid token for a new identity.
 */
export function setInstalledIdentity(sub: string | null, tid: string | null): void {
  installedSub = sub;
  installedTid = tid;
}

export function getInstalledIdentity(): { sub: string | null; tid: string | null } {
  return { sub: installedSub, tid: installedTid };
}

/** Starts an identity switch by clearing existing in-memory state. */
export function startSessionSwitch(): void {
  clearSession();
}

/** Completes an identity switch by pinning the new installed identity. */
export function completeSessionSwitch(sub: string | null, tid: string | null): void {
  setInstalledIdentity(sub, tid);
}

/** Bypasses the 10-second recent refresh cache to force a fresh server refresh under Web Locks. */
export async function forceServerRefresh(): Promise<boolean> {
  lastRefreshTime = 0;
  return silentRefresh();
}

// Helpers exported for testing
export function _resetClientForTesting() {
  accessToken = null;
  refreshPromise = null;
  lastRefreshTime = 0;
  installedSub = null;
  installedTid = null;
  authListeners.clear();
}

/**
 * Fetch wrapper that adds Authorization header and retries once on 401.
 */
export async function apiFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  if (!headers.has('Authorization') && accessToken && isTokenValid(accessToken)) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  if (!headers.has('Content-Type') && options.body) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(url, { ...options, headers, credentials: 'include' });

  // If 401 and we had a token, try silent refresh once
  if (res.status === 401 && accessToken) {
    const refreshed = await silentRefresh();
    if (refreshed && accessToken && isTokenValid(accessToken)) {
      const retryHeaders = new Headers(options.headers);
      retryHeaders.set('Authorization', `Bearer ${accessToken}`);
      if (!retryHeaders.has('Content-Type') && options.body) {
        retryHeaders.set('Content-Type', 'application/json');
      }
      return fetch(url, { ...options, headers: retryHeaders, credentials: 'include' });
    }
  }

  return res;
}
