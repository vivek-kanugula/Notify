import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  type ReactNode,
} from 'react';
import {
  setAdminAccessToken,
  adminSilentRefresh,
  onAdminAuthChange,
} from '../api/adminClient';
import {
  adminLogin as apiAdminLogin,
  adminLogout as apiAdminLogout,
  adminGetMe,
  type AdminUserInfo,
} from '../api/adminAuth';

interface AdminAuthContextType {
  admin: AdminUserInfo | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AdminAuthContext = createContext<AdminAuthContextType | null>(null);

export function useAdminAuth(): AdminAuthContextType {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) throw new Error('useAdminAuth must be used within AdminAuthProvider');
  return ctx;
}

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [admin, setAdmin] = useState<AdminUserInfo | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchMe = useCallback(async () => {
    try {
      const data = await adminGetMe();
      setAdmin(data);
    } catch {
      setAdmin(null);
      setAdminAccessToken(null);
    }
  }, []);

  useEffect(() => {
    let mounted = true;

    // Listen for tab sync auth changes
    const unsubscribe = onAdminAuthChange((token) => {
      if (!mounted) return;
      if (token) {
        fetchMe();
      } else {
        setAdmin(null);
      }
    });

    // Initial silent refresh on app startup
    const initAuth = async () => {
      try {
        const refreshed = await adminSilentRefresh();
        if (refreshed && mounted) {
          await fetchMe();
        }
      } catch {
        // Not logged in as admin
      } finally {
        if (mounted) setLoading(false);
      }
    };

    initAuth();

    return () => {
      mounted = false;
      unsubscribe();
    };
  }, [fetchMe]);

  const login = async (email: string, password: string) => {
    await apiAdminLogin(email, password);
    await fetchMe();
  };

  const logout = async () => {
    await apiAdminLogout();
    setAdmin(null);
  };

  return (
    <AdminAuthContext.Provider value={{ admin, loading, login, logout }}>
      {children}
    </AdminAuthContext.Provider>
  );
}
