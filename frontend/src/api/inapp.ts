import { apiFetch } from './client';

export interface InAppMessage {
  /** The cursor. A decimal string, never a number: a 64-bit seq would be rounded beyond 2^53. */
  seq: string;
  notificationId: string;
  subject: string | null;
  body: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface InAppMessagePage {
  content: InAppMessage[];
  page: { size: number; hasMore: boolean };
  nextCursor: string | null;
  unread: number;
}

export interface StreamToken {
  token: string;
  expiresInSeconds: number;
  streamUrl: string;
  admittedStreams: number;
  capPerTenant: number;
}

/** A refusal the browser can actually read, unlike anything EventSource reports. */
export interface StreamTokenRefused {
  refused: true;
  status: number;
  retryAfterSeconds: number;
  detail: string;
}

export async function listInAppMessages(
  recipientRef: string,
  options: { after?: string | null; size?: number } = {},
): Promise<InAppMessagePage> {
  const params = new URLSearchParams({ recipientRef });
  if (options.after) params.set('after', options.after);
  if (options.size) params.set('size', String(options.size));
  const response = await apiFetch(`/api/v1/inapp-messages?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to load the inbox (${response.status})`);
  }
  return response.json();
}

export async function markInAppMessageRead(recipientRef: string, seq: string): Promise<void> {
  const params = new URLSearchParams({ recipientRef });
  const response = await apiFetch(`/api/v1/inapp-messages/${seq}/read?${params.toString()}`, {
    method: 'POST',
  });
  if (!response.ok) {
    throw new Error(`Failed to mark the message read (${response.status})`);
  }
}

/**
 * Requests a single-use stream token.
 *
 * This is the browser's readiness and backoff channel. `EventSource.onerror` exposes no HTTP status, no
 * `Retry-After` and no response body, so a client can learn nothing from a refused stream. This endpoint is ordinary
 * authenticated JSON, so a refusal - the per-tenant cap, or the provenance barrier after admission state was lost -
 * arrives as a readable status with a retry hint.
 */
export async function requestStreamToken(
  recipientRef: string,
): Promise<StreamToken | StreamTokenRefused> {
  const response = await apiFetch('/api/v1/inapp/stream-tokens', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ recipientRef }),
  });
  if (response.ok) {
    return response.json();
  }
  let retryAfterSeconds = Number(response.headers.get('Retry-After') ?? 0);
  let detail = `Stream unavailable (${response.status})`;
  try {
    const problem = await response.json();
    if (problem?.retryAfterSeconds) retryAfterSeconds = Number(problem.retryAfterSeconds);
    if (problem?.detail) detail = problem.detail;
  } catch {
    // A non-JSON error body is fine; the status and the header are enough to back off.
  }
  return {
    refused: true,
    status: response.status,
    retryAfterSeconds: retryAfterSeconds > 0 ? retryAfterSeconds : 30,
    detail,
  };
}
