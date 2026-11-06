import { apiFetch } from './client.ts';

export interface WebhookEndpoint {
  id: string;
  url: string;
  isDemo: boolean;
  enabled: boolean;
  statusCallbacks: boolean;
  createdAt: string;
  updatedAt: string;
  signingSecret?: string; // Present only on create or rotate response
}

export interface WebhookTestResult {
  delivered: boolean;
  httpStatus: number | null;
  outcome: string;
  errorCode: string | null;
  detail: string | null;
}

export async function getWebhookEndpoint(): Promise<WebhookEndpoint | null> {
  const res = await apiFetch('/api/v1/webhook-endpoint');
  if (res.status === 404) {
    return null;
  }
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch webhook endpoint' }));
    throw new Error(error.detail || error.errorCode || 'Failed to fetch webhook endpoint');
  }
  return res.json();
}

export async function updateWebhookEndpoint(request: {
  url: string;
  enabled?: boolean;
}): Promise<WebhookEndpoint> {
  const res = await apiFetch('/api/v1/webhook-endpoint', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to update webhook endpoint' }));
    throw new Error(error.detail || error.errorCode || 'Failed to update webhook endpoint');
  }
  return res.json();
}

export async function testWebhookEndpoint(): Promise<WebhookTestResult> {
  const res = await apiFetch('/api/v1/webhook-endpoint/test', {
    method: 'POST',
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to test webhook ping' }));
    throw new Error(error.detail || error.errorCode || 'Failed to test webhook ping');
  }
  return res.json();
}

export async function rotateWebhookSecret(): Promise<WebhookEndpoint> {
  const res = await apiFetch('/api/v1/webhook-endpoint/rotate-secret', {
    method: 'POST',
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to rotate webhook secret' }));
    throw new Error(error.detail || error.errorCode || 'Failed to rotate webhook secret');
  }
  return res.json();
}

export async function deleteWebhookEndpoint(): Promise<void> {
  const res = await apiFetch('/api/v1/webhook-endpoint', {
    method: 'DELETE',
  });
  if (!res.ok && res.status !== 204) {
    const error = await res.json().catch(() => ({ detail: 'Failed to delete webhook endpoint' }));
    throw new Error(error.detail || error.errorCode || 'Failed to delete webhook endpoint');
  }
}
