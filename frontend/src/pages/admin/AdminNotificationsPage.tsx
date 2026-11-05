import { useState, useEffect, useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  searchAdminNotifications,
  type AdminNotificationListItem,
} from '../../api/adminNotifications';
import type { NotificationStatus } from '../../api/notifications';
import {
  Search,
  ChevronLeft,
  ChevronRight,
  ShieldAlert,
  Bell,
  RotateCcw,
} from 'lucide-react';

export default function AdminNotificationsPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const [notifications, setNotifications] = useState<AdminNotificationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Read filters from URL search params
  const initialTenantName = searchParams.get('tenantName') || '';
  const initialTenantId = searchParams.get('tenantId') || '';
  const initialStatus = (searchParams.get('status') as NotificationStatus) || '';
  const initialChannel = (searchParams.get('channel') as 'EMAIL' | 'WEBHOOK' | 'IN_APP') || '';
  const initialPage = parseInt(searchParams.get('page') || '0', 10);

  const [tenantName, setTenantName] = useState(initialTenantName);
  const [tenantId, setTenantId] = useState(initialTenantId);
  const [status, setStatus] = useState<NotificationStatus | ''>(initialStatus);
  const [channel, setChannel] = useState<'EMAIL' | 'WEBHOOK' | 'IN_APP' | ''>(initialChannel);
  const [page, setPage] = useState(initialPage);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const fetchNotifications = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await searchAdminNotifications({
        tenantName: tenantName.trim() || undefined,
        tenantId: tenantId.trim() || undefined,
        status: status || undefined,
        channel: channel || undefined,
        page,
        size: 20,
      });
      setNotifications(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err: any) {
      setError(err.message || 'Failed to search notifications');
    } finally {
      setLoading(false);
    }
  }, [tenantName, tenantId, status, channel, page]);

  useEffect(() => {
    fetchNotifications();

    // Sync to URL
    const params = new URLSearchParams();
    if (tenantName) params.set('tenantName', tenantName);
    if (tenantId) params.set('tenantId', tenantId);
    if (status) params.set('status', status);
    if (channel) params.set('channel', channel);
    if (page > 0) params.set('page', String(page));
    setSearchParams(params, { replace: true });
  }, [fetchNotifications, tenantName, tenantId, status, channel, page, setSearchParams]);

  const getStatusChipClass = (st: NotificationStatus) => {
    switch (st) {
      case 'DELIVERED':
        return 'chip-delivered';
      case 'FAILED':
        return 'chip-failed';
      case 'PROCESSING':
        return 'chip-processing';
      case 'RETRYING':
        return 'chip-retrying';
      case 'DLQ':
        return 'chip-cancelled';
      case 'CANCELLED':
        return 'chip-cancelled';
      default:
        return 'chip-queued';
    }
  };

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 4px', color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
            System Notification Explorer
          </h1>
          <p style={{ fontSize: 13, color: 'var(--color-ink-2)', margin: 0 }}>
            Cross-tenant inspection of all notification delivery lifecycles, states, and provider dispatch records.
          </p>
        </div>
        <div style={{ fontSize: 12, color: 'var(--color-ink-2)', backgroundColor: 'var(--color-chalk)', padding: '6px 12px', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
          Total matches: <strong className="mono" style={{ color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>{totalElements}</strong>
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
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 200 }}>
          <Search size={14} style={{ color: 'var(--color-ink-2)' }} />
          <input
            type="text"
            placeholder="Filter by Tenant Name…"
            value={tenantName}
            onChange={(e) => {
              setTenantName(e.target.value);
              setPage(0);
            }}
            className="input-text"
            style={{ border: 'none', padding: '6px 0', fontSize: 12, width: '100%' }}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 200 }}>
          <Search size={14} style={{ color: 'var(--color-ink-2)' }} />
          <input
            type="text"
            placeholder="Filter by Tenant UUID…"
            value={tenantId}
            onChange={(e) => {
              setTenantId(e.target.value);
              setPage(0);
            }}
            className="input-text mono"
            style={{ border: 'none', padding: '6px 0', fontSize: 12, width: '100%' }}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as NotificationStatus | '');
              setPage(0);
            }}
            className="select-control"
            style={{ fontSize: 12, padding: '4px 8px', width: 'auto' }}
          >
            <option value="">All Statuses</option>
            <option value="QUEUED">Queued</option>
            <option value="PROCESSING">Processing</option>
            <option value="RETRYING">Retrying</option>
            <option value="DELIVERED">Delivered</option>
            <option value="FAILED">Failed</option>
            <option value="DLQ">DLQ</option>
            <option value="CANCELLED">Cancelled</option>
          </select>

          <select
            value={channel}
            onChange={(e) => {
              setChannel(e.target.value as any);
              setPage(0);
            }}
            className="select-control"
            style={{ fontSize: 12, padding: '4px 8px', width: 'auto' }}
          >
            <option value="">All Channels</option>
            <option value="EMAIL">Email</option>
            <option value="WEBHOOK">Webhook</option>
            <option value="IN_APP">In-App</option>
          </select>

          {(tenantName || tenantId || status || channel) && (
            <button
              type="button"
              onClick={() => {
                setTenantName('');
                setTenantId('');
                setStatus('');
                setChannel('');
                setPage(0);
              }}
              className="btn btn-secondary"
              style={{ fontSize: 11, padding: '4px 8px' }}
            >
              <RotateCcw size={11} /> Reset
            </button>
          )}
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

      {/* Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
            <span style={{ fontSize: 13 }}>Loading notifications…</span>
          </div>
        ) : notifications.length === 0 ? (
          <div style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div style={{ width: 36, height: 36, borderRadius: 'var(--radius-control)', background: 'var(--color-chalk)', color: 'var(--color-ink-2)', display: 'grid', placeItems: 'center', margin: '0 auto 12px' }}>
              <Bell size={18} />
            </div>
            <p style={{ fontWeight: 600, color: 'var(--color-harbor)', margin: '0 0 4px' }}>No notifications matched the criteria</p>
            <p style={{ fontSize: 12, margin: 0 }}>Adjust your search parameters or clear filters.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Notification ID</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Tenant</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Channel</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Recipient</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Subject</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Created</th>
                </tr>
              </thead>
              <tbody>
                {notifications.map((n) => (
                  <tr key={n.id} style={{ borderBottom: '1px solid var(--color-rail)' }}>
                    <td style={{ padding: '10px 14px' }}>
                      <Link
                        to={`/admin/notifications/${n.id}`}
                        className="mono"
                        style={{
                          fontSize: 11,
                          fontWeight: 600,
                          color: 'var(--color-signal)',
                          textDecoration: 'none',
                        }}
                      >
                        {n.id.slice(0, 8)}…{n.id.slice(-4)}
                      </Link>
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <Link
                        to={`/admin/tenants/${n.tenantId}`}
                        style={{
                          textDecoration: 'none',
                          display: 'inline-block',
                        }}
                        title={`Tenant: ${n.tenantName || 'Unknown'} (ID: ${n.tenantId})`}
                      >
                        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-signal)', display: 'block' }}>
                          {n.tenantName || `${n.tenantId.slice(0, 8)}…`}
                        </span>
                        {n.tenantName && (
                          <span className="mono" style={{ fontSize: 10, color: 'var(--color-ink-2)', display: 'block' }}>
                            {n.tenantId.slice(0, 8)}…
                          </span>
                        )}
                      </Link>
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <span
                        style={{
                          fontSize: 10,
                          fontWeight: 600,
                          padding: '2px 6px',
                          borderRadius: 'var(--radius-chip)',
                          backgroundColor: 'var(--color-chalk)',
                          border: '1px solid var(--color-rail)',
                          color: 'var(--color-harbor)',
                        }}
                      >
                        {n.channel}
                      </span>
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <span className={`chip ${getStatusChipClass(n.status)}`} style={{ fontSize: 10, padding: '1px 6px' }}>
                        {n.status}
                      </span>
                    </td>
                    <td className="mono" style={{ padding: '10px 14px', fontSize: 11, maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={n.recipient}>
                      {n.recipient}
                    </td>
                    <td style={{ padding: '10px 14px', fontSize: 12, maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={n.subject || undefined}>
                      {n.subject || <span style={{ color: 'var(--color-ink-2)' }}>—</span>}
                    </td>
                    <td className="mono" style={{ padding: '10px 14px', fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
                      {new Date(n.createdAt).toLocaleDateString()} {new Date(n.createdAt).toLocaleTimeString()}
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
    </div>
  );
}