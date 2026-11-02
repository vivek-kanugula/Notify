import { adminFetch } from './adminClient.ts';

export interface LatencyPercentilesMs {
  p50: number;
  p95: number;
  p99: number;
}

export interface TopTenantVolume {
  tenantId: string;
  slug: string;
  name: string;
  volume: number;
}

export interface TopFailureReason {
  reason: string;
  count: number;
}

export interface AdminAnalyticsOverviewResponse {
  from: string;
  to: string;
  totalNotifications: number;
  deliveredCount: number;
  failedCount: number;
  cancelledCount: number;
  activeCount: number;
  globalSuccessRate: number;
  latencyPercentilesMs: LatencyPercentilesMs;
  queueDepth: number;
  dlqOpenCount: number;
  topTenants: TopTenantVolume[];
  topFailureReasons: TopFailureReason[];
}

export interface TenantUsageSummaryResponse {
  tenantId: string;
  from: string;
  to: string;
  totalCreated: number;
  deliveredCount: number;
  failedCount: number;
  cancelledCount: number;
  activeCount: number;
  channelBreakdown: Record<string, number>;
  statusBreakdown: Record<string, number>;
}

export async function getAdminOverviewAnalytics(params?: {
  from?: string;
  to?: string;
}): Promise<AdminAnalyticsOverviewResponse> {
  const query = new URLSearchParams();
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);

  const queryString = query.toString();
  const endpoint = `/api/v1/admin/analytics/overview${queryString ? `?${queryString}` : ''}`;
  const response = await adminFetch(endpoint);
  if (!response.ok) {
    throw new Error(`Failed to fetch admin overview analytics (HTTP ${response.status})`);
  }
  return response.json();
}

export async function getAdminTenantUsage(
  tenantId: string,
  params?: { from?: string; to?: string }
): Promise<TenantUsageSummaryResponse> {
  const query = new URLSearchParams();
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);

  const queryString = query.toString();
  const endpoint = `/api/v1/admin/tenants/${tenantId}/usage${queryString ? `?${queryString}` : ''}`;
  const response = await adminFetch(endpoint);
  if (!response.ok) {
    throw new Error(`Failed to fetch tenant usage (HTTP ${response.status})`);
  }
  return response.json();
}
