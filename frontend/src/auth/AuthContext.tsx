import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  type ReactNode,
} from 'react';
import {
  setAccessToken,
  getAccessToken,
  silentRefresh,
  apiFetch,
  onAuthChange,
  setInstalledIdentity,
  parseJwtPayload,
} from '../api/client';

interface User {
  userId: string;
  email: string;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  tenantStatus: string;
  roles: string[];
}

interface AuthContextType {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (tenantName: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchMe = useCallback(async () => {
    try {
      const res = await apiFetch('/auth/me');
      if (res.ok) {
        const data = await res.json();
        setUser(data);
      } else {
        setUser(null);
        setAccessToken(null);
      }
    } catch {
      setUser(null);
      setAccessToken(null);
    }
  }, []);

  // Sync state when tokens change across tabs
  useEffect(() => {
    const unsubscribe = onAuthChange((token) => {
      if (!token) {
        setUser(null);
      } else {
        fetchMe();
      }
    });
    return unsubscribe;
  }, [fetchMe]);

  // Silent refresh on app load
  useEffect(() => {
    async function init() {
      setLoading(true);
      const refreshed = await silentRefresh();
      if (refreshed) {
        await fetchMe();
      }
      setLoading(false);
    }
    init();
  }, [fetchMe]);

  const login = useCallback(async (email: string, password: string) => {
    let res: Response;
    try {
      res = await fetch('/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, password }),
      });
    } catch {
      throw new Error('Unable to connect to server. Please ensure the backend is running.');
    }

    if (!res.ok) {
      const error = await res.json().catch(() => null);
      if (res.status === 401) {
        throw new Error(error?.detail || 'Invalid email or password.');
      }
      if (res.status === 429) {
        throw new Error(error?.detail || 'Too many login attempts. Please wait a moment before trying again.');
      }
      if (res.status >= 500) {
        throw new Error(error?.detail || 'Server error. Please verify backend services.');
      }
      throw new Error(error?.detail || 'Login failed');
    }

    const data = await res.json();
    setAccessToken(data.accessToken);
    // Record identity so broadcast sub-check works correctly
    const payload = parseJwtPayload(data.accessToken);
    if (payload) {
      setInstalledIdentity(
        (payload.sub as string | undefined) ?? null,
        (payload.tid as string | undefined) ?? null,
      );
    }
    await fetchMe();
  }, [fetchMe]);

  const signup = useCallback(async (tenantName: string, email: string, password: string) => {
    let res: Response;
    try {
      res = await fetch('/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ tenantName, email, password }),
      });
    } catch {
      throw new Error('Unable to connect to server. Please ensure the backend is running.');
    }

    if (!res.ok) {
      const error = await res.json().catch(() => null);
      if (res.status === 409) {
        throw new Error(error?.detail || 'An account with this email or organization name already exists.');
      }
      if (res.status >= 500) {
        throw new Error(error?.detail || 'Server error. Please verify backend services.');
      }
      throw new Error(error?.detail || 'Signup failed');
    }

    const data = await res.json();
    setAccessToken(data.accessToken);
    const payload = parseJwtPayload(data.accessToken);
    if (payload) {
      setInstalledIdentity(
        (payload.sub as string | undefined) ?? null,
        (payload.tid as string | undefined) ?? null,
      );
    }
    await fetchMe();
  }, [fetchMe]);

  const logout = useCallback(async () => {
    try {
      const token = getAccessToken();
      const headers: Record<string, string> = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
      await apiFetch('/auth/logout', { method: 'POST', headers });
    } catch {
      // Continue with local cleanup even if server logout fails
    }
    // Session clear triggers route unmount, which cleans up any open SSE streams
    setInstalledIdentity(null, null);
    setAccessToken(null);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider
      value={{ user, loading, login, signup, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}
