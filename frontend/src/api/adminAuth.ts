import { adminFetch, setAdminAccessToken, clearAdminSession, getAdminAccessToken } from './adminClient.ts';

export interface AdminUserInfo {
  adminId: string;
  email: string;
  fullName: string;
  roles: string[];
}

export interface AdminAuthResponse {
  accessToken: string;
  expiresIn: number;
}

export async function adminLogin(email: string, password: string): Promise<AdminAuthResponse> {
  let res: Response;
  try {
    res = await adminFetch('/auth/admin/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
  } catch {
    throw new Error('Unable to connect to server. Please ensure the backend is running.');
  }

  if (!res.ok) {
    const err = await res.json().catch(() => null);
    if (res.status === 401) {
      throw new Error(err?.detail || 'Invalid operator credentials.');
    }
    if (res.status === 429) {
      throw new Error(err?.detail || 'Too many login attempts. Please wait a moment before trying again.');
    }
    if (res.status >= 500) {
      throw new Error(err?.detail || 'Server error. Please verify operator backend services.');
    }
    throw new Error(err?.detail || 'Admin login failed');
  }

  const data: AdminAuthResponse = await res.json();
  setAdminAccessToken(data.accessToken);
  return data;
}

export async function adminLogout(): Promise<void> {
  const token = getAdminAccessToken();
  try {
    const headers: Record<string, string> = {};
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    await adminFetch('/auth/admin/logout', {
      method: 'POST',
      headers,
    });
  } finally {
    clearAdminSession();
  }
}

export async function adminGetMe(): Promise<AdminUserInfo> {
  const res = await adminFetch('/auth/admin/me');
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to fetch admin profile');
  }
  return res.json();
}
