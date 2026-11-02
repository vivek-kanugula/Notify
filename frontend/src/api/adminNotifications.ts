import { adminFetch } from './adminClient.ts';
import type { PageResponse } from './adminTenants.ts';
import type { NotificationStatus, NotificationHistoryEntry } from './notifications.ts';

export interface AdminNotificationListItem {
  id: string;
  tenantId: string;
  channel: 'EMAIL' | 'WEBHOOK' | 'IN_APP';
  status: NotificationStatus;
  recipient: string;
  subject: string | null;
  createdAt: string;
}

export interface DeliveryAttemptView {
  attemptNumber: number;
  provider: string;
  status: string;
  errorCode: string | null;
  httpStatus: number | null;
  attemptedAt: string;
  durationMs: number | null;
}

export interface AdminNotificationDetail {
  id: string;
  tenantId: string;
  channel: 'EMAIL' | 'WEBHOOK' | 'IN_APP';
  status: NotificationStatus;
  recipient: string;
  subject: string | null;
  scheduledAt: string | null;
  createdAt: string;
  firstProcessingAt: string | null;
  completedAt: string | null;
  attemptCount: number;
  lastErrorCode: string | null;
  provider: string | null;
  providerMessageId: string | null;
  metadata: string | null;
  deliveryAttempts: DeliveryAttemptView[];
  statusHistory: NotificationHistoryEntry[];
}

export interface AdminNotificationSearchParams {
  tenantId?: string;
  status?: NotificationStatus | '';
  channel?: 'EMAIL' | 'WEBHOOK' | 'IN_APP' | '';
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export async function searchAdminNotifications(
  params: AdminNotificationSearchParams = {}
): Promise<PageResponse<AdminNotificationListItem>> {
  const searchParams = new URLSearchParams();
  if (params.tenantId?.trim()) searchParams.set('tenantId', params.tenantId.trim());
  if (params.status) searchParams.set('status', params.status);
  if (params.channel) searchParams.set('channel', params.channel);
  if (params.from) searchParams.set('from', params.from);
  if (params.to) searchParams.set('to', params.to);
  if (params.page !== undefined) searchParams.set('page', String(params.page));
  if (params.size !== undefined) searchParams.set('size', String(params.size));

  const qs = searchParams.toString();
  const url = `/api/v1/admin/notifications${qs ? `?${qs}` : ''}`;
  const res = await adminFetch(url);

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to search notifications');
  }

  return res.json();
}

export async function getAdminNotificationDetail(
  notificationId: string
): Promise<AdminNotificationDetail> {
  const res = await adminFetch(`/api/v1/admin/notifications/${notificationId}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to fetch notification detail');
  }
  return res.json();
}
