import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import {
  listAdminTenants,
  updateAdminTenantStatus,
  deleteAdminTenant,
  type AdminTenantListItem,
  type TenantStatus,
} from '../../api/adminTenants';
import TenantStatusModal from './TenantStatusModal';
import TenantDeleteModal from './TenantDeleteModal';
import {
  Search,
  Users,
  Key,
  FileCode,
  ShieldAlert,
  ChevronLeft,
  ChevronRight,
  Eye,
  Trash2,
  Building,
} from 'lucide-react';

export default function AdminTenantsPage() {
  const [tenants, setTenants] = useState<AdminTenantListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Filters & Pagination
  const [statusFilter, setStatusFilter] = useState<TenantStatus | ''>('');
  const [searchQuery, setSearchQuery] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Modals state
  const [statusModalTenant, setStatusModalTenant] = useState<{
    tenant: AdminTenantListItem;
    targetStatus: TenantStatus;
  } | null>(null);

  const [deleteModalTenant, setDeleteModalTenant] = useState<AdminTenantListItem | null>(null);

  const fetchTenants = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAdminTenants({
        page,
        size: 15,
        status: statusFilter,
        query: searchQuery,
      });
      setTenants(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err: any) {
      setError(err.message || 'Failed to fetch tenants');
    } finally {
      setLoading(false);
    }
  }, [page, statusFilter, searchQuery]);

  useEffect(() => {
    fetchTenants();
  }, [fetchTenants]);

  const handleStatusChange = async (reason: string) => {
    if (!statusModalTenant) return;
    await updateAdminTenantStatus(statusModalTenant.tenant.id, {
      status: statusModalTenant.targetStatus,
      reason,
    });
    await fetchTenants();
  };

  const handleDelete = async (confirmSlug: string, reason?: string) => {
    if (!deleteModalTenant) return;
    await deleteAdminTenant(deleteModalTenant.id, confirmSlug, reason);
    await fetchTenants();
  };

  const getStatusChip = (status: TenantStatus) => {
    switch (status) {
      case 'ACTIVE':
        return (
          <span className="chip chip-delivered" style={{ fontSize: 11, padding: '1px 7px' }}>
            Active
          </span>
        );
      case 'SUSPENDED':
        return (
          <span className="chip chip-retrying" style={{ fontSize: 11, padding: '1px 7px' }}>
            Suspended
          </span>
        );
      case 'REVOKED':
        return (
          <span className="chip chip-cancelled" style={{ fontSize: 11, padding: '1px 7px' }}>
            Revoked
          </span>
        );
    }
  };

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Page Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 4px', color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
            Tenants Management
          </h1>
          <p style={{ fontSize: 13, color: 'var(--color-ink-2)', margin: 0 }}>
            Manage platform tenants, monitor provisioning, and enforce isolation lifecycle transitions.
          </p>
        </div>
        <div style={{ fontSize: 12, color: 'var(--color-ink-2)', backgroundColor: 'var(--color-chalk)', padding: '6px 12px', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
          Total registered: <strong className="mono" style={{ color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>{totalElements}</strong>
        </div>
      </div>

      {/* Filter Bar */}
      <div
        className="panel"
        style={{
          padding: '10px 14px',
          marginBottom: 16,
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 220 }}>
          <Search size={14} style={{ color: 'var(--color-ink-2)' }} />
          <input
            type="text"
            placeholder="Search by organization name or slug…"
            value={searchQuery}
            onChange={(e) => {
              setSearchQuery(e.target.value);
              setPage(0);
            }}
            className="input-text"
            style={{ border: 'none', padding: '6px 0', fontSize: 12 }}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 11, color: 'var(--color-ink-2)', fontWeight: 600 }}>Status:</span>
          <select
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value as TenantStatus | '');
              setPage(0);
            }}
            className="select-control"
            style={{ fontSize: 12, padding: '4px 8px', width: 'auto' }}
          >
            <option value="">All Statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="SUSPENDED">Suspended</option>
            <option value="REVOKED">Revoked</option>
          </select>
        </div>
      </div>

      {error && (
        <div
          style={{
            padding: '10px 14px',
            borderRadius: 'var(--radius-control)',
            marginBottom: 16,
            fontSize: 12,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            backgroundColor: 'var(--color-st-failed-bg)',
            color: 'var(--color-st-failed)',
            border: '1px solid var(--color-st-failed)',
          }}
        >
          <ShieldAlert size={15} />
          <span>{error}</span>
        </div>
      )}

      {/* Table Panel */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
            <span style={{ fontSize: 13 }}>Loading tenants…</span>
          </div>
        ) : tenants.length === 0 ? (
          <div style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div style={{ width: 36, height: 36, borderRadius: 'var(--radius-control)', background: 'var(--color-chalk)', color: 'var(--color-ink-2)', display: 'grid', placeItems: 'center', margin: '0 auto 12px' }}>
              <Building size={18} />
            </div>
            <p style={{ fontWeight: 600, color: 'var(--color-harbor)', margin: '0 0 4px' }}>No tenants found</p>
            <p style={{ fontSize: 12, margin: 0 }}>Try clearing search keywords or status filters.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Tenant</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Provisioned Resources</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Created</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status Changed</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600, textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {tenants.map((t) => (
                  <tr key={t.id} style={{ borderBottom: '1px solid var(--color-rail)' }}>
                    <td style={{ padding: '12px 14px' }}>
                      <div>
                        <Link
                          to={`/admin/tenants/${t.id}`}
                          style={{
                            fontWeight: 600,
                            color: 'var(--color-signal)',
                            textDecoration: 'none',
                            fontSize: 13,
                          }}
                        >
                          {t.name}
                        </Link>
                        <div className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 2 }}>
                          {t.slug}
                        </div>
                      </div>
                    </td>
                    <td style={{ padding: '12px 14px' }}>
                      <div>
                        {getStatusChip(t.status)}
                        {t.statusReason && (
                          <div
                            style={{
                              fontSize: 11,
                              color: 'var(--color-ink-2)',
                              marginTop: 3,
                              maxWidth: 180,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                            title={t.statusReason}
                          >
                            {t.statusReason}
                          </div>
                        )}
                      </div>
                    </td>
                    <td style={{ padding: '12px 14px' }}>
                      <div style={{ display: 'flex', gap: 12, fontSize: 11, color: 'var(--color-ink-2)' }}>
                        <span title="Users" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                          <Users size={12} /> <strong className="mono" style={{ color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>{t.userCount}</strong>
                        </span>
                        <span title="Active API Keys" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                          <Key size={12} /> <strong className="mono" style={{ color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>{t.activeApiKeyCount}</strong>
                        </span>
                        <span title="Templates" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                          <FileCode size={12} /> <strong className="mono" style={{ color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>{t.templateCount}</strong>
                        </span>
                      </div>
                    </td>
                    <td style={{ padding: '12px 14px', fontSize: 11, color: 'var(--color-ink-2)' }}>
                      <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>
                        {new Date(t.createdAt).toLocaleDateString()}
                      </span>
                    </td>
                    <td style={{ padding: '12px 14px', fontSize: 11, color: 'var(--color-ink-2)' }}>
                      <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>
                        {t.statusChangedAt ? new Date(t.statusChangedAt).toLocaleDateString() : '—'}
                      </span>
                    </td>
                    <td style={{ padding: '12px 14px', textAlign: 'right' }}>
                      <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <Link
                          to={`/admin/tenants/${t.id}`}
                          className="btn btn-secondary"
                          style={{ padding: '4px 8px', fontSize: 11, textDecoration: 'none' }}
                        >
                          <Eye size={11} /> View
                        </Link>

                        {t.status === 'ACTIVE' && (
                          <button
                            type="button"
                            onClick={() => setStatusModalTenant({ tenant: t, targetStatus: 'SUSPENDED' })}
                            className="btn btn-secondary"
                            style={{ padding: '4px 8px', fontSize: 11, color: 'var(--color-st-retrying)' }}
                          >
                            Suspend
                          </button>
                        )}

                        {t.status === 'SUSPENDED' && (
                          <button
                            type="button"
                            onClick={() => setStatusModalTenant({ tenant: t, targetStatus: 'ACTIVE' })}
                            className="btn btn-secondary"
                            style={{ padding: '4px 8px', fontSize: 11, color: 'var(--color-st-delivered)' }}
                          >
                            Reactivate
                          </button>
                        )}

                        {t.status !== 'REVOKED' && (
                          <button
                            type="button"
                            onClick={() => setStatusModalTenant({ tenant: t, targetStatus: 'REVOKED' })}
                            className="btn btn-secondary"
                            style={{ padding: '4px 8px', fontSize: 11, color: 'var(--color-st-failed)' }}
                          >
                            Revoke
                          </button>
                        )}

                        {t.status === 'REVOKED' && (
                          <button
                            type="button"
                            onClick={() => setStatusModalTenant({ tenant: t, targetStatus: 'ACTIVE' })}
                            className="btn btn-secondary"
                            style={{ padding: '4px 8px', fontSize: 11, color: 'var(--color-st-delivered)' }}
                          >
                            Reactivate
                          </button>
                        )}

                        <button
                          type="button"
                          onClick={() => setDeleteModalTenant(t)}
                          className="btn btn-danger"
                          style={{ padding: '4px 8px', fontSize: 11 }}
                          title="Permanently delete tenant"
                        >
                          <Trash2 size={11} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Footer */}
        {totalPages > 1 && (
          <div
            style={{
              padding: '10px 16px',
              borderTop: '1px solid var(--color-rail)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontSize: 12,
              color: 'var(--color-ink-2)',
            }}
          >
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>
              Page {page + 1} of {totalPages}
            </span>
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                type="button"
                disabled={page === 0 || loading}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="btn btn-secondary"
                style={{ padding: '4px 8px', fontSize: 11 }}
              >
                <ChevronLeft size={13} />
              </button>
              <button
                type="button"
                disabled={page >= totalPages - 1 || loading}
                onClick={() => setPage((p) => p + 1)}
                className="btn btn-secondary"
                style={{ padding: '4px 8px', fontSize: 11 }}
              >
                <ChevronRight size={13} />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Modals */}
      {statusModalTenant && (
        <TenantStatusModal
          isOpen={true}
          onClose={() => setStatusModalTenant(null)}
          tenantId={statusModalTenant.tenant.id}
          tenantName={statusModalTenant.tenant.name}
          tenantSlug={statusModalTenant.tenant.slug}
          currentStatus={statusModalTenant.tenant.status}
          targetStatus={statusModalTenant.targetStatus}
          onConfirm={handleStatusChange}
        />
      )}

      {deleteModalTenant && (
        <TenantDeleteModal
          isOpen={true}
          onClose={() => setDeleteModalTenant(null)}
          tenantId={deleteModalTenant.id}
          tenantName={deleteModalTenant.name}
          tenantSlug={deleteModalTenant.slug}
          onConfirm={handleDelete}
        />
      )}
    </div>
  );
}