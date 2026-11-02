import { adminFetch } from './adminClient.ts';

export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'REVOKED';

export interface AdminTenantListItem {
  id: string;
  slug: string;
  name: string;
  status: TenantStatus;
  statusReason: string | null;
  statusChangedAt: string | null;
  createdAt: string;
  userCount: number;
  activeApiKeyCount: number;
  templateCount: number;
}

export interface AdminTenantDetail {
  id: string;
  slug: string;
  name: string;
  status: TenantStatus;
  statusReason: string | null;
  statusChangedAt: string | null;
  createdAt: string;
  updatedAt: string;
  userCount: number;
  activeApiKeyCount: number;
  templateCount: number;
  hasWebhookConfigured: boolean;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface UpdateTenantStatusRequest {
  status: TenantStatus;
  reason: string;
}

export async function listAdminTenants(params: {
  page?: number;
  size?: number;
  status?: TenantStatus | '';
  query?: string;
} = {}): Promise<PageResponse<AdminTenantListItem>> {
  const searchParams = new URLSearchParams();
  if (params.page !== undefined) searchParams.set('page', String(params.page));
  if (params.size !== undefined) searchParams.set('size', String(params.size));
  if (params.status) searchParams.set('status', params.status);
  if (params.query?.trim()) searchParams.set('query', params.query.trim());

  const qs = searchParams.toString();
  const url = `/api/v1/admin/tenants${qs ? `?${qs}` : ''}`;
  const res = await adminFetch(url);

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to list tenants');
  }

  return res.json();
}

export async function getAdminTenant(tenantId: string): Promise<AdminTenantDetail> {
  const res = await adminFetch(`/api/v1/admin/tenants/${tenantId}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || 'Failed to get tenant details');
  }
  return res.json();
}

export async function updateAdminTenantStatus(
  tenantId: string,
  request: UpdateTenantStatusRequest
): Promise<AdminTenantListItem> {
  const res = await adminFetch(`/api/v1/admin/tenants/${tenantId}/status`, {
    method: 'PATCH',
    body: JSON.stringify(request),
  });

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const error = new Error(err.detail || 'Failed to update tenant status') as any;
    error.status = res.status;
    error.errorCode = err.errorCode;
    throw error;
  }

  return res.json();
}

export async function deleteAdminTenant(
  tenantId: string,
  confirmSlug: string,
  reason?: string
): Promise<void> {
  const searchParams = new URLSearchParams();
  if (reason?.trim()) {
    searchParams.set('reason', reason.trim());
  }
  const qs = searchParams.toString();
  const url = `/api/v1/admin/tenants/${tenantId}${qs ? `?${qs}` : ''}`;

  const res = await adminFetch(url, {
    method: 'DELETE',
    headers: {
      'X-Confirm-Tenant-Slug': confirmSlug,
    },
  });

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const error = new Error(err.detail || 'Failed to delete tenant') as any;
    error.status = res.status;
    error.errorCode = err.errorCode;
    throw error;
  }
}
