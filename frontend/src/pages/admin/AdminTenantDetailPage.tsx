import { useState, useEffect, useCallback } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import {
  getAdminTenant,
  updateAdminTenantStatus,
  deleteAdminTenant,
  type AdminTenantDetail,
  type TenantStatus,
} from '../../api/adminTenants';
import {
  searchAdminNotifications,
  type AdminNotificationListItem,
} from '../../api/adminNotifications';
import {
  listAdminAuditLog,
  type AdminAuditLogEntry,
} from '../../api/adminAudit';
import TenantStatusModal from './TenantStatusModal';
import TenantDeleteModal from './TenantDeleteModal';
import {
  ArrowLeft,
  Users,
  Key,
  FileCode,
  Webhook,
  Bell,
  FileText,
  AlertCircle,
} from 'lucide-react';

export default function AdminTenantDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [tenant, setTenant] = useState<AdminTenantDetail | null>(null);
  const [recentNotifications, setRecentNotifications] = useState<AdminNotificationListItem[]>([]);
  const [recentAudit, setRecentAudit] = useState<AdminAuditLogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modals
  const [targetStatus, setTargetStatus] = useState<TenantStatus | null>(null);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);

  const fetchTenantData = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const data = await getAdminTenant(id);
      setTenant(data);

      const [notifsData, auditData] = await Promise.all([
        searchAdminNotifications({ tenantId: id, size: 5 }).catch(() => ({ content: [] })),
        listAdminAuditLog({ tenantId: id, size: 5 }).catch(() => ({ content: [] })),
      ]);

      setRecentNotifications(notifsData.content);
      setRecentAudit(auditData.content);
    } catch (err: any) {
      setError(err.message || 'Failed to fetch tenant detail');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchTenantData();
  }, [fetchTenantData]);

  const handleStatusChange = async (reason: string) => {
    if (!id || !targetStatus) return;
    await updateAdminTenantStatus(id, { status: targetStatus, reason });
    await fetchTenantData();
  };

  const handleDelete = async (confirmSlug: string, reason?: string) => {
    if (!id) return;
    await deleteAdminTenant(id, confirmSlug, reason);
    navigate('/admin/tenants', { replace: true });
  };

  if (loading) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div className="panel" style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
          <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
          <span style={{ fontSize: 13 }}>Loading tenant details…</span>
        </div>
      </div>
    );
  }

  if (error || !tenant) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div style={{ marginBottom: 16 }}>
          <Link to="/admin/tenants" className="btn btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
            <ArrowLeft size={13} /> Back to Tenants
          </Link>
        </div>
        <div className="panel" style={{ padding: 24, borderLeft: '3px solid var(--color-st-failed)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-st-failed)', fontWeight: 600, marginBottom: 4 }}>
            <AlertCircle size={16} /> Error loading tenant
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>{error || 'Tenant not found'}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Back button */}
      <div style={{ marginBottom: 16 }}>
        <Link
          to="/admin/tenants"
          className="btn btn-secondary"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 12,
            textDecoration: 'none',
          }}
        >
          <ArrowLeft size={13} />
          <span>Back to Tenants</span>
        </Link>
      </div>

      {/* Header Banner */}
      <div className="panel" style={{ padding: 22, marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 4 }}>
              <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
                {tenant.name}
              </h1>
              <span
                className={`chip ${
                  tenant.status === 'ACTIVE'
                    ? 'chip-delivered'
                    : tenant.status === 'SUSPENDED'
                    ? 'chip-retrying'
                    : 'chip-cancelled'
                }`}
                style={{ fontSize: 11, padding: '2px 8px' }}
              >
                {tenant.status}
              </span>
            </div>

            <div style={{ fontSize: 12, color: 'var(--color-ink-2)' }}>
              <span>Slug: <strong className="mono" style={{ color: 'var(--color-harbor)' }}>{tenant.slug}</strong></span>
              <span style={{ margin: '0 8px' }}>•</span>
              <span>Tenant ID: <code className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)' }}>{tenant.id}</code></span>
            </div>

            {tenant.statusReason && (
              <div
                style={{
                  marginTop: 10,
                  padding: '6px 12px',
                  backgroundColor: 'var(--color-chalk)',
                  border: '1px solid var(--color-rail)',
                  borderRadius: 'var(--radius-control)',
                  fontSize: 11,
                  color: 'var(--color-ink-2)',
                  display: 'inline-block',
                }}
              >
                Status Reason: <em style={{ color: 'var(--color-harbor)' }}>"{tenant.statusReason}"</em>
              </div>
            )}
          </div>

          {/* Action Buttons */}
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {tenant.status === 'ACTIVE' && (
              <button
                type="button"
                onClick={() => setTargetStatus('SUSPENDED')}
                className="btn btn-secondary"
                style={{ fontSize: 12, color: 'var(--color-st-retrying)' }}
              >
                Suspend Access
              </button>
            )}

            {tenant.status === 'SUSPENDED' && (
              <button
                type="button"
                onClick={() => setTargetStatus('ACTIVE')}
                className="btn btn-secondary"
                style={{ fontSize: 12, color: 'var(--color-st-delivered)' }}
              >
                Reactivate Tenant
              </button>
            )}

            {tenant.status !== 'REVOKED' && (
              <button
                type="button"
                onClick={() => setTargetStatus('REVOKED')}
                className="btn btn-secondary"
                style={{ fontSize: 12, color: 'var(--color-st-failed)' }}
              >
                Revoke Access
              </button>
            )}

            {tenant.status === 'REVOKED' && (
              <button
                type="button"
                onClick={() => setTargetStatus('ACTIVE')}
                className="btn btn-secondary"
                style={{ fontSize: 12, color: 'var(--color-st-delivered)' }}
              >
                Reactivate Tenant
              </button>
            )}

            <button
              type="button"
              onClick={() => setIsDeleteOpen(true)}
              className="btn btn-danger"
              style={{ fontSize: 12 }}
            >
              Delete Tenant
            </button>
          </div>
        </div>

        {/* Resource KPI Grid */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: 12,
            marginTop: 20,
            paddingTop: 18,
            borderTop: '1px solid var(--color-rail)',
          }}
        >
          <div style={{ padding: 12, backgroundColor: 'var(--color-chalk)', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--color-ink-2)', fontSize: 11, fontWeight: 600 }}>
              <Users size={13} /> Total Users
            </div>
            <div className="mono" style={{ fontSize: 20, fontWeight: 700, color: 'var(--color-harbor)', marginTop: 4, fontVariantNumeric: 'tabular-nums' }}>
              {tenant.userCount}
            </div>
          </div>

          <div style={{ padding: 12, backgroundColor: 'var(--color-chalk)', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--color-ink-2)', fontSize: 11, fontWeight: 600 }}>
              <Key size={13} /> Active API Keys
            </div>
            <div className="mono" style={{ fontSize: 20, fontWeight: 700, color: 'var(--color-harbor)', marginTop: 4, fontVariantNumeric: 'tabular-nums' }}>
              {tenant.activeApiKeyCount}
            </div>
            <span style={{ fontSize: 10, color: 'var(--color-ink-2)', marginTop: 2, display: 'block' }}>Prefix & status only</span>
          </div>

          <div style={{ padding: 12, backgroundColor: 'var(--color-chalk)', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--color-ink-2)', fontSize: 11, fontWeight: 600 }}>
              <FileCode size={13} /> Templates
            </div>
            <div className="mono" style={{ fontSize: 20, fontWeight: 700, color: 'var(--color-harbor)', marginTop: 4, fontVariantNumeric: 'tabular-nums' }}>
              {tenant.templateCount}
            </div>
          </div>

          <div style={{ padding: 12, backgroundColor: 'var(--color-chalk)', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--color-ink-2)', fontSize: 11, fontWeight: 600 }}>
              <Webhook size={13} /> Webhook Status
            </div>
            <div style={{ fontSize: 14, fontWeight: 700, color: tenant.hasWebhookConfigured ? 'var(--color-st-delivered)' : 'var(--color-ink-2)', marginTop: 6 }}>
              {tenant.hasWebhookConfigured ? 'CONFIGURED' : 'NONE'}
            </div>
          </div>
        </div>
      </div>

      {/* Detail Sections Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))', gap: 20 }}>
        {/* Recent Notifications */}
        <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
          <div
            style={{
              padding: '12px 18px',
              borderBottom: '1px solid var(--color-rail)',
              backgroundColor: 'var(--color-chalk)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Bell size={15} style={{ color: 'var(--color-signal)' }} />
              <h2 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Recent Tenant Notifications
              </h2>
            </div>
            <Link
              to={`/admin/notifications?tenantId=${tenant.id}`}
              style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-signal)', textDecoration: 'none' }}
            >
              View All →
            </Link>
          </div>

          {recentNotifications.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-ink-2)', fontSize: 12 }}>
              No notifications recorded for this tenant yet.
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Channel</th>
                    <th>Status</th>
                    <th>Created</th>
                  </tr>
                </thead>
                <tbody>
                  {recentNotifications.map((n) => (
                    <tr key={n.id}>
                      <td>
                        <Link
                          to={`/admin/notifications/${n.id}`}
                          className="mono"
                          style={{ fontSize: 11, color: 'var(--color-signal)', textDecoration: 'none', fontWeight: 600 }}
                        >
                          {n.id.slice(0, 8)}…
                        </Link>
                      </td>
                      <td>
                        <span style={{ fontSize: 10, padding: '1px 5px', borderRadius: 3, backgroundColor: 'var(--color-chalk)', border: '1px solid var(--color-rail)', fontWeight: 600 }}>
                          {n.channel}
                        </span>
                      </td>
                      <td>
                        <span className="chip chip-delivered" style={{ fontSize: 10, padding: '1px 6px' }}>
                          {n.status}
                        </span>
                      </td>
                      <td className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
                        {new Date(n.createdAt).toLocaleTimeString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Recent Audit Events */}
        <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
          <div
            style={{
              padding: '12px 18px',
              borderBottom: '1px solid var(--color-rail)',
              backgroundColor: 'var(--color-chalk)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <FileText size={15} style={{ color: 'var(--color-signal)' }} />
              <h2 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Recent Audit Trail
              </h2>
            </div>
            <Link
              to={`/admin/audit?tenantId=${tenant.id}`}
              style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-signal)', textDecoration: 'none' }}
            >
              View All →
            </Link>
          </div>

          {recentAudit.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-ink-2)', fontSize: 12 }}>
              No audit actions recorded for this tenant.
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                <thead>
                  <tr>
                    <th>Action</th>
                    <th>Operator</th>
                    <th>Time</th>
                  </tr>
                </thead>
                <tbody>
                  {recentAudit.map((a) => (
                    <tr key={a.id}>
                      <td>
                        <span className="mono" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-harbor)' }}>
                          {a.action}
                        </span>
                      </td>
                      <td style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>{a.adminEmail}</td>
                      <td className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
                        {new Date(a.createdAt).toLocaleTimeString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Modals */}
      {targetStatus && (
        <TenantStatusModal
          isOpen={true}
          onClose={() => setTargetStatus(null)}
          tenantId={tenant.id}
          tenantName={tenant.name}
          tenantSlug={tenant.slug}
          currentStatus={tenant.status}
          targetStatus={targetStatus}
          onConfirm={handleStatusChange}
        />
      )}

      {isDeleteOpen && (
        <TenantDeleteModal
          isOpen={true}
          onClose={() => setIsDeleteOpen(false)}
          tenantId={tenant.id}
          tenantName={tenant.name}
          tenantSlug={tenant.slug}
          onConfirm={handleDelete}
        />
      )}
    </div>
  );
}