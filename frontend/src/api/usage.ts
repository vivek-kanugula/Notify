import { apiFetch } from './client';
import type { Channel } from './notifications';

export interface ChannelCounts {
  accepted: number;
  delivered: number;
  failed: number;
}

export interface UsageSummary {
  tenantId: string;
  from: string;
  to: string;
  totalAccepted: number;
  totalDelivered: number;
  totalFailed: number;
  totalCancelled: number;
  totalProcessing: number;
  deliverySuccessRate: number;
  byChannel: Record<Channel, ChannelCounts>;
  statusCounts?: Record<string, number>;
}

export interface UsageBucket {
  bucket: string;
  delivered: number;
  failed: number;
  cancelled: number;
  successRate: number;
}

export interface UsageTimeseries {
  from: string;
  to: string;
  interval: string;
  buckets: UsageBucket[];
}

export async function getUsageSummary(params?: {
  from?: string;
  to?: string;
}): Promise<UsageSummary> {
  const query = new URLSearchParams();
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);

  const qs = query.toString();
  const res = await apiFetch(`/api/v1/usage/summary${qs ? `?${qs}` : ''}`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch usage summary' }));
    throw new Error(error.detail || error.errorCode || 'Failed to fetch usage summary');
  }
  return res.json();
}

export async function getUsageTimeseries(params?: {
  from?: string;
  to?: string;
  interval?: 'day' | 'hour';
}): Promise<UsageTimeseries> {
  const query = new URLSearchParams();
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);
  if (params?.interval) query.set('interval', params.interval);

  const qs = query.toString();
  const res = await apiFetch(`/api/v1/usage/timeseries${qs ? `?${qs}` : ''}`);
  if (!res.ok) {
    const error = await res.json().catch(() => ({ detail: 'Failed to fetch usage timeseries' }));
    throw new Error(error.detail || error.errorCode || 'Failed to fetch usage timeseries');
  }
  return res.json();
}
