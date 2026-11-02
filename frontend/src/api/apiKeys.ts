import { apiFetch } from './client';

export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  scopes: string[];
  status: 'ACTIVE' | 'REVOKED';
  expiresAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
  rotatedToId?: string | null;
}

export interface ApiKeyCreated extends ApiKey {
  apiKey: string;
}

export interface CreateApiKeyRequest {
  name: string;
  scopes: string[];
  expiresAt?: string | null;
}

export interface RotateApiKeyRequest {
  name?: string;
  scopes?: string[];
  expiresAt?: string | null;
  gracePeriodHours?: number;
}

export async function listApiKeys(): Promise<ApiKey[]> {
  const res = await apiFetch('/api/v1/keys');
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch API keys' }));
    throw new Error(error.detail || 'Failed to fetch API keys');
  }
  return res.json();
}

export async function createApiKey(data: CreateApiKeyRequest): Promise<ApiKeyCreated> {
  const res = await apiFetch('/api/v1/keys', {
    method: 'POST',
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to create API key' }));
    throw new Error(error.detail || error.errorCode || 'Failed to create API key');
  }
  return res.json();
}

export async function revokeApiKey(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/keys/${id}`, {
    method: 'DELETE',
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to revoke API key' }));
    throw new Error(error.detail || 'Failed to revoke API key');
  }
}

export async function rotateApiKey(id: string, data?: RotateApiKeyRequest): Promise<ApiKeyCreated> {
  const res = await apiFetch(`/api/v1/keys/${id}/rotate`, {
    method: 'POST',
    body: JSON.stringify(data || {}),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to rotate API key' }));
    throw new Error(error.detail || error.errorCode || 'Failed to rotate API key');
  }
  return res.json();
}
