/**
 * Dedicated Admin API client with automatic 401 -> silent refresh -> retry.
 * Admin access token kept in memory only (never in localStorage/sessionStorage/cookies).
 * Strictly isolated from tenant client.ts. Uses separate Web Lock and BroadcastChannel.
 */

let adminAccessToken: string | null = null;
let adminRefreshPromise: Promise<boolean> | null = null;
let lastAdminRefreshTime = 0;

type AdminAuthChangeListener = (token: string | null) => void;
const adminAuthListeners = new Set<AdminAuthChangeListener>();

export function onAdminAuthChange(listener: AdminAuthChangeListener): () => void {
  adminAuthListeners.add(listener);
  return () => {
    adminAuthListeners.delete(listener);
  };
}

// In-memory tab synchronization channel specifically for admin sessions
const adminAuthChannel: BroadcastChannel | null =
  typeof BroadcastChannel !== 'undefined'
    ? new BroadcastChannel('notify_admin_auth_tab_sync')
    : null;

if (adminAuthChannel && typeof (adminAuthChannel as any).unref === 'function') {
  (adminAuthChannel as any).unref();
}

function getTabId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

const currentTabId = getTabId();

/**
 * Parses JWT payload without external libraries.
 */
export function parseJwtPayload(token: string): { exp?: number; aud?: string | string[]; sub?: string; roles?: string[]; [key: string]: any } | null {
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
 * Validates an admin JWT against its expiration claim (`exp`) and audience (`aud`).
 */
export function isAdminTokenValid(token: string | null, bufferSeconds = 10): boolean {
  if (!token) return false;
  const payload = parseJwtPayload(token);
  if (!payload || typeof payload.exp !== 'number') return false;
  const audMatch = Array.isArray(payload.aud)
    ? payload.aud.includes('notify-admin')
    : payload.aud === 'notify-admin';
  if (!audMatch) return false;

  const now = Math.floor(Date.now() / 1000);
  return payload.exp - now > bufferSeconds;
}

export function setAdminAccessToken(token: string | null): void {
  adminAccessToken = token;
  adminAuthListeners.forEach((fn) => fn(token));
}

export function getAdminAccessToken(): string | null {
  return adminAccessToken;
}

export function clearAdminSession(): void {
  setAdminAccessToken(null);
  adminRefreshPromise = null;
  lastAdminRefreshTime = 0;
  if (adminAuthChannel) {
    adminAuthChannel.postMessage({ type: 'ADMIN_LOGOUT', sourceTabId: currentTabId });
  }
}

if (adminAuthChannel) {
  adminAuthChannel.onmessage = (event) => {
    if (!event.data || typeof event.data !== 'object') return;
    if (event.data.sourceTabId === currentTabId) return;

    if (event.data.type === 'ADMIN_LOGOUT') {
      setAdminAccessToken(null);
      adminRefreshPromise = null;
      lastAdminRefreshTime = 0;
    } else if (event.data.type === 'ADMIN_REFRESH_SUCCESS' && event.data.accessToken) {
      const incomingToken = event.data.accessToken;
      if (isAdminTokenValid(incomingToken)) {
        setAdminAccessToken(incomingToken);
        lastAdminRefreshTime = Date.now();
      }
    }
  };
}

/**
 * Performs silent refresh against POST /auth/admin/refresh using the notify_admin_refresh cookie.
 * Synchronized across tabs via Web Locks API.
 */
export async function adminSilentRefresh(): Promise<boolean> {
  const now = Date.now();
  if (adminAccessToken && isAdminTokenValid(adminAccessToken) && now - lastAdminRefreshTime < 10_000) {
    return true;
  }

  if (adminRefreshPromise) {
    return adminRefreshPromise;
  }

  const execute = async (): Promise<boolean> => {
    if (typeof navigator === 'undefined' || !navigator.locks) {
      try {
        const res = await fetch('/auth/admin/refresh', {
          method: 'POST',
          credentials: 'include',
        });
        if (res.ok) {
          const data = await res.json();
          if (isAdminTokenValid(data.accessToken)) {
            setAdminAccessToken(data.accessToken);
            lastAdminRefreshTime = Date.now();
            return true;
          }
        }
        setAdminAccessToken(null);
        return false;
      } catch {
        setAdminAccessToken(null);
        return false;
      }
    }

    const doRefresh = async () => {
      if (adminAccessToken && isAdminTokenValid(adminAccessToken)) {
        return true;
      }

      try {
        const res = await fetch('/auth/admin/refresh', {
          method: 'POST',
          credentials: 'include',
        });
        if (res.ok) {
          const data = await res.json();
          if (isAdminTokenValid(data.accessToken)) {
            setAdminAccessToken(data.accessToken);
            lastAdminRefreshTime = Date.now();
            if (adminAuthChannel) {
              adminAuthChannel.postMessage({
                type: 'ADMIN_REFRESH_SUCCESS',
                accessToken: data.accessToken,
                sourceTabId: currentTabId,
              });
            }
            return true;
          }
        }
        setAdminAccessToken(null);
        return false;
      } catch {
        setAdminAccessToken(null);
        return false;
      }
    };

    return navigator.locks.request('notify_admin_refresh_tab_lock', doRefresh);
  };

  adminRefreshPromise = execute().finally(() => {
    adminRefreshPromise = null;
  });

  return adminRefreshPromise;
}

/**
 * Admin fetch wrapper that adds Authorization: Bearer <adminToken> and retries once on 401.
 */
export async function adminFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  if (!headers.has('Authorization') && adminAccessToken && isAdminTokenValid(adminAccessToken)) {
    headers.set('Authorization', `Bearer ${adminAccessToken}`);
  }
  if (!headers.has('Content-Type') && options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(url, { ...options, headers, credentials: 'include' });

  // If 401 and we had a token, try silent refresh once
  if (res.status === 401 && adminAccessToken) {
    const refreshed = await adminSilentRefresh();
    if (refreshed && adminAccessToken && isAdminTokenValid(adminAccessToken)) {
      const retryHeaders = new Headers(options.headers);
      retryHeaders.set('Authorization', `Bearer ${adminAccessToken}`);
      if (!retryHeaders.has('Content-Type') && options.body && !(options.body instanceof FormData)) {
        retryHeaders.set('Content-Type', 'application/json');
      }
      return fetch(url, { ...options, headers: retryHeaders, credentials: 'include' });
    }
  }

  return res;
}

export function _resetAdminClientForTesting() {
  adminAccessToken = null;
  adminRefreshPromise = null;
  lastAdminRefreshTime = 0;
  adminAuthListeners.clear();
}
