import { apiFetch } from './client';

export type Channel = 'EMAIL' | 'WEBHOOK' | 'IN_APP';
export type NotificationStatus =
  | 'CREATED'
  | 'SCHEDULED'
  | 'QUEUED'
  | 'PROCESSING'
  | 'RETRYING'
  | 'DELIVERED'
  | 'FAILED'
  | 'DLQ'
  | 'CANCELLED';

export interface NotificationSummary {
  id: string;
  channel: Channel;
  status: NotificationStatus;
  recipient: string;
  subject: string | null;
  scheduledAt: string | null;
  createdAt: string;
}

export interface NotificationDetail {
  id: string;
  tenantId: string;
  channel: Channel;
  status: NotificationStatus;
  recipient: string;
  subject: string | null;
  body: string;
  templateVersionId: string | null;
  templateVariables: Record<string, any> | null;
  metadata: Record<string, any> | null;
  idempotencyKey: string | null;
  scheduledAt: string | null;
  queuedAt: string | null;
  processingStartedAt: string | null;
  completedAt: string | null;
  attemptCount: number;
  maxAttempts: number;
  lastErrorCode: string | null;
  provider: string | null;
  dispatchGeneration: number;
  deliveryCycle: number;
  createdAt: string;
  updatedAt: string;
}

export interface NotificationHistoryEntry {
  id: number;
  fromStatus: NotificationStatus | null;
  toStatus: NotificationStatus;
  reason: string | null;
  actor: 'SYSTEM' | 'TENANT' | 'ADMIN';
  occurredAt: string;
}

export interface CreateNotificationRequest {
  channel: Channel;
  recipient: string;
  subject?: string | null;
  body?: string | null;
  templateId?: string | null;
  templateVersion?: number | null;
  templateVariables?: Record<string, any> | null;
  metadata?: Record<string, any> | null;
  idempotencyKey?: string | null;
  scheduledAt?: string | null;
  replyTo?: string | null;
}

export interface NotificationAcceptanceResponse {
  id: string;
  status: NotificationStatus;
}

export interface CancelNotificationResponse {
  id: string;
  status: NotificationStatus;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export async function listNotifications(params?: {
  channel?: Channel;
  status?: NotificationStatus;
  page?: number;
  size?: number;
}): Promise<PageResponse<NotificationSummary>> {
  const query = new URLSearchParams();
  if (params?.channel) query.set('channel', params.channel);
  if (params?.status) query.set('status', params.status);
  if (params?.page !== undefined) query.set('page', String(params.page));
  if (params?.size !== undefined) query.set('size', String(params.size));

  const qs = query.toString();
  const url = `/api/v1/notifications${qs ? `?${qs}` : ''}`;
  const res = await apiFetch(url);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch notifications' }));
    throw new Error(error.detail || error.errorCode || 'Failed to fetch notifications');
  }
  return res.json();
}

export async function getNotification(id: string): Promise<NotificationDetail> {
  const res = await apiFetch(`/api/v1/notifications/${id}`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch notification detail' }));
    throw new Error(error.detail || error.errorCode || 'Failed to fetch notification detail');
  }
  return res.json();
}

export async function getNotificationHistory(id: string): Promise<NotificationHistoryEntry[]> {
  const res = await apiFetch(`/api/v1/notifications/${id}/history`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch status history' }));
    throw new Error(error.detail || error.errorCode || 'Failed to fetch status history');
  }
  return res.json();
}

export async function createNotification(
  data: CreateNotificationRequest,
  idempotencyKey?: string
): Promise<{ data: NotificationAcceptanceResponse; isReplay: boolean }> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }

  const res = await apiFetch('/api/v1/notifications', {
    method: 'POST',
    headers,
    body: JSON.stringify(data),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to accept notification' }));
    const err = new Error(error.detail || error.errorCode || 'Failed to accept notification') as any;
    err.status = res.status;
    err.errorCode = error.errorCode;
    throw err;
  }

  const isReplay = res.headers.get('Idempotent-Replay') === 'true';
  const body = await res.json();
  return { data: body, isReplay };
}

export async function cancelNotification(id: string, reason?: string): Promise<CancelNotificationResponse> {
  const res = await apiFetch(`/api/v1/notifications/${id}/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(reason ? { reason } : {}),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to cancel notification' }));
    throw new Error(error.detail || error.errorCode || 'Failed to cancel notification');
  }

  return res.json();
}
