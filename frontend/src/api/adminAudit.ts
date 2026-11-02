import { adminFetch } from './adminClient.ts';
import type { PageResponse } from './adminTenants.ts';

export interface AdminAuditLogEntry {
  id: number;
  adminId: string;
  adminEmail: string;
  action: string;
  targetType: string;
  targetId: string;
  tenantId: string | null;
  tenantSlug: string | null;
  reason: string | null;
  details: string | null;
  ipAddress: string;
  createdAt: string;
}

export interface AdminAuditSearchParams {
  page?: number;
  size?: number;
  tenantId?: string;
  action?: string;
  targetType?: string;
  from?: string;
  to?: string;
}

export async function listAdminAuditLog(
  params: AdminAuditSearchParams = {}
): Promise<PageResponse<AdminAuditLogEntry>> {
  const searchParams = new URLSearchParams();
  if (params.page !== undefined) searchParams.set('page', String(params.page));
  if (params.size !== undefined) searchParams.set('size', String(params.size));
  if (params.tenantId?.trim()) searchParams.set('tenantId', params.tenantId.trim());
  if (params.action?.trim()) searchParams.set('action', params.action.trim());
  if (params.targetType?.trim()) searchParams.set('targetType', params.targetType.trim());
  if (params.from) searchParams.set('from', params.from);
  if (params.to) searchParams.set('to', params.to);

  const qs = searchParams.toString();
  const url = `/api/v1/admin/audit-log${qs ? `?${qs}` : ''}`;
  const res = await adminFetch(url);

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to list audit log entries');
  }

  return res.json();
}
