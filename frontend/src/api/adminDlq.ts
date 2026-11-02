import { adminFetch } from './adminClient.ts';

export type DlqStatus = 'OPEN' | 'RESOLVED' | 'DISCARDED';
export type DlqReason = 'EXHAUSTED' | 'UNRECOVERABLE_FAILURE' | 'INVALID_PAYLOAD' | 'RATE_LIMIT_EXCEEDED';
export type Channel = 'EMAIL' | 'WEBHOOK' | 'IN_APP';

export interface DlqPage<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface DlqEntrySummary {
  entryId: string;
  notificationId: string;
  tenantId: string;
  tenantSlug: string;
  channel: Channel;
  reason: DlqReason;
  lastErrorCode: string | null;
  attemptCount: number;
  status: DlqStatus;
  replayCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface DlqAttemptView {
  attemptNumber: number;
  deliveryCycle: number;
  outcome: string;
  errorCode: string | null;
  httpStatus: number | null;
  provider: string;
  latencyMs: number | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface DlqHistoryView {
  fromStatus: string | null;
  toStatus: string;
  actor: string;
  reason: string | null;
  occurredAt: string;
}

export interface DlqNotificationSnapshot {
  currentStatus: string;
  channel: Channel;
  currentDeliveryCycle: number;
  currentDispatchGeneration: number;
  currentAttemptCount: number;
  maxAttempts: number;
}

export interface DlqEntryDetail extends DlqEntrySummary {
  sourceDeliveryCycle: number | null;
  sourceGeneration: number | null;
  payloadSnapshot: string; // Inert dead-letter envelope JSON
  resolutionNote: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  notification: DlqNotificationSnapshot;
  attempts: DlqAttemptView[];
  attemptTotalCount: number;
  attemptsTruncated: boolean;
  history: DlqHistoryView[];
  historyTotalCount: number;
  historyTruncated: boolean;
  quarantineRecordCount: number;
}

export interface DlqReplayResponse {
  entryId: string;
  notificationId: string;
  status: DlqStatus;
  replayCount: number;
  message: string;
}

export async function listAdminDlqEntries(params?: {
  status?: string;
  channel?: string;
  reason?: string;
  tenantId?: string;
  from?: string;
  to?: string;
  cursor?: string;
  limit?: number;
}): Promise<DlqPage<DlqEntrySummary>> {
  const query = new URLSearchParams();
  if (params?.status) query.set('status', params.status);
  if (params?.channel) query.set('channel', params.channel);
  if (params?.reason) query.set('reason', params.reason);
  if (params?.tenantId) query.set('tenantId', params.tenantId);
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);
  if (params?.cursor) query.set('cursor', params.cursor);
  if (params?.limit) query.set('limit', String(params.limit));

  const queryString = query.toString();
  const endpoint = `/api/v1/admin/dlq${queryString ? `?${queryString}` : ''}`;
  const response = await adminFetch(endpoint);
  if (!response.ok) {
    throw new Error(`Failed to list DLQ entries (HTTP ${response.status})`);
  }
  return response.json();
}

export async function getAdminDlqEntryDetail(entryId: string): Promise<DlqEntryDetail> {
  const response = await adminFetch(`/api/v1/admin/dlq/${entryId}`);
  if (!response.ok) {
    throw new Error(`Failed to get DLQ entry detail (HTTP ${response.status})`);
  }
  return response.json();
}

export async function getAdminDlqAttempts(entryId: string, limit?: number): Promise<DlqPage<DlqAttemptView>> {
  const query = new URLSearchParams();
  if (limit) query.set('limit', String(limit));
  const queryString = query.toString();
  const endpoint = `/api/v1/admin/dlq/${entryId}/attempts${queryString ? `?${queryString}` : ''}`;
  const response = await adminFetch(endpoint);
  if (!response.ok) {
    throw new Error(`Failed to get DLQ attempts (HTTP ${response.status})`);
  }
  return response.json();
}

export async function getAdminDlqHistory(entryId: string, limit?: number): Promise<DlqPage<DlqHistoryView>> {
  const query = new URLSearchParams();
  if (limit) query.set('limit', String(limit));
  const queryString = query.toString();
  const endpoint = `/api/v1/admin/dlq/${entryId}/history${queryString ? `?${queryString}` : ''}`;
  const response = await adminFetch(endpoint);
  if (!response.ok) {
    throw new Error(`Failed to get DLQ history (HTTP ${response.status})`);
  }
  return response.json();
}

export async function replayDlqEntry(entryId: string, note?: string): Promise<DlqReplayResponse> {
  const body = note && note.trim().length > 0 ? { note: note.trim() } : {};
  const response = await adminFetch(`/api/v1/admin/dlq/${entryId}/replay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    throw new Error(errorData?.detail || `Failed to replay DLQ entry (HTTP ${response.status})`);
  }
  return response.json();
}

export async function discardDlqEntry(entryId: string, note: string): Promise<DlqEntryDetail> {
  if (!note || note.trim().length < 3) {
    throw new Error('A discard note of at least 3 characters is required.');
  }
  const response = await adminFetch(`/api/v1/admin/dlq/${entryId}/discard`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ note: note.trim() }),
  });
  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    throw new Error(errorData?.detail || `Failed to discard DLQ entry (HTTP ${response.status})`);
  }
  return response.json();
}
