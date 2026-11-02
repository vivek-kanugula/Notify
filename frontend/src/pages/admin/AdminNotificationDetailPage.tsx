import { useState, useEffect, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  getAdminNotificationDetail,
  type AdminNotificationDetail,
} from '../../api/adminNotifications';
import LifecycleRail from '../../components/LifecycleRail';
import {
  ArrowLeft,
  AlertCircle,
  Copy,
  Check,
} from 'lucide-react';

export default function AdminNotificationDetailPage() {
  const { id } = useParams<{ id: string }>();

  const [notification, setNotification] = useState<AdminNotificationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState(false);

  const fetchDetail = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const data = await getAdminNotificationDetail(id);
      setNotification(data);
    } catch (err: any) {
      setError(err.message || 'Failed to fetch notification detail');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  const handleCopyId = async () => {
    if (!notification) return;
    try {
      await navigator.clipboard.writeText(notification.id);
      setCopiedId(true);
      setTimeout(() => setCopiedId(false), 2000);
    } catch {}
  };

  if (loading) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div className="panel" style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
          <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
          <span style={{ fontSize: 13 }}>Loading notification details…</span>
        </div>
      </div>
    );
  }

  if (error || !notification) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div style={{ marginBottom: 16 }}>
          <Link to="/admin/notifications" className="btn btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
            <ArrowLeft size={13} /> Back to Notifications
          </Link>
        </div>
        <div className="panel" style={{ padding: 24, borderLeft: '3px solid var(--color-st-failed)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-st-failed)', fontWeight: 600, marginBottom: 4 }}>
            <AlertCircle size={16} /> Error loading notification
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>{error || 'Notification not found'}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Back button */}
      <div style={{ marginBottom: 16 }}>
        <Link
          to="/admin/notifications"
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
          <span>Back to Notifications</span>
        </Link>
      </div>

      {/* Main Header & Lifecycle Rail Panel */}
      <div className="panel" style={{ padding: 22, marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16 }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 4 }}>
              <h1 className="mono" style={{ fontSize: 18, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
                {notification.id}
              </h1>
              <button
                type="button"
                onClick={handleCopyId}
                title="Copy notification ID"
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                {copiedId ? <Check size={13} style={{ color: 'var(--color-st-delivered)' }} /> : <Copy size={13} style={{ color: 'var(--color-ink-2)' }} />}
              </button>
              <span
                style={{
                  padding: '2px 8px',
                  borderRadius: 'var(--radius-chip)',
                  fontSize: 11,
                  fontWeight: 600,
                  backgroundColor: 'var(--color-chalk)',
                  border: '1px solid var(--color-rail)',
                  color: 'var(--color-harbor)',
                }}
              >
                {notification.channel}
              </span>
            </div>

            <div style={{ fontSize: 12, color: 'var(--color-ink-2)' }}>
              <span>Tenant ID: </span>
              <Link
                to={`/admin/tenants/${notification.tenantId}`}
                className="mono"
                style={{ color: 'var(--color-signal)', textDecoration: 'none', fontWeight: 600 }}
              >
                {notification.tenantId}
              </Link>
            </div>
          </div>

          <div className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
            Created: {new Date(notification.createdAt).toLocaleString()}
          </div>
        </div>

        {/* Signature Lifecycle Rail */}
        <div style={{ marginTop: 22, marginBottom: 8 }}>
          <LifecycleRail
            status={notification.status}
            scheduledAt={notification.scheduledAt}
            queuedAt={notification.createdAt}
            processingStartedAt={notification.firstProcessingAt}
            completedAt={notification.completedAt}
            createdAt={notification.createdAt}
            attemptCount={notification.attemptCount}
            history={notification.statusHistory}
            variant="full"
          />
        </div>
      </div>

      {/* Two-Column Details Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 20, marginBottom: 20 }}>
        {/* Attributes Panel */}
        <div className="panel" style={{ padding: 20 }}>
          <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)', margin: '0 0 16px', letterSpacing: '-0.01em' }}>
            Notification Attributes
          </h2>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--color-rail)', paddingBottom: 8 }}>
              <span style={{ color: 'var(--color-ink-2)' }}>Recipient</span>
              <span className="mono" style={{ fontWeight: 600, color: 'var(--color-harbor)' }}>{notification.recipient}</span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--color-rail)', paddingBottom: 8 }}>
              <span style={{ color: 'var(--color-ink-2)' }}>Subject</span>
              <span style={{ fontWeight: 600, color: 'var(--color-harbor)' }}>{notification.subject || '—'}</span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--color-rail)', paddingBottom: 8 }}>
              <span style={{ color: 'var(--color-ink-2)' }}>Provider</span>
              <span className="mono">{notification.provider || '—'}</span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--color-rail)', paddingBottom: 8 }}>
              <span style={{ color: 'var(--color-ink-2)' }}>Provider Msg ID</span>
              <span className="mono" style={{ fontSize: 11 }}>{notification.providerMessageId || '—'}</span>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--color-rail)', paddingBottom: 8 }}>
              <span style={{ color: 'var(--color-ink-2)' }}>Total Attempts</span>
              <span className="mono" style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{notification.attemptCount}</span>
            </div>

            {notification.lastErrorCode && (
              <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--color-rail)', paddingBottom: 8 }}>
                <span style={{ color: 'var(--color-st-failed)' }}>Last Error Code</span>
                <span className="mono" style={{ fontWeight: 700, color: 'var(--color-st-failed)' }}>
                  {notification.lastErrorCode}
                </span>
              </div>
            )}

            {notification.metadata && (
              <div style={{ marginTop: 6 }}>
                <span style={{ color: 'var(--color-ink-2)', display: 'block', marginBottom: 4, fontWeight: 600 }}>Metadata</span>
                <pre
                  className="mono"
                  style={{
                    padding: 10,
                    backgroundColor: 'var(--color-chalk)',
                    border: '1px solid var(--color-rail)',
                    borderRadius: 'var(--radius-control)',
                    fontSize: 11,
                    overflowX: 'auto',
                    margin: 0,
                    lineHeight: 1.5,
                  }}
                >
                  {notification.metadata}
                </pre>
              </div>
            )}
          </div>
        </div>

        {/* Status Transition History */}
        <div className="panel" style={{ padding: 20 }}>
          <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)', margin: '0 0 16px', letterSpacing: '-0.01em' }}>
            Status Transition History
          </h2>

          {notification.statusHistory.length === 0 ? (
            <div style={{ color: 'var(--color-ink-2)', fontSize: 12 }}>No status history recorded.</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {notification.statusHistory.map((h, i) => (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 10,
                    borderLeft: '2px solid var(--color-rail)',
                    paddingLeft: 12,
                    marginLeft: 2,
                  }}
                >
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span className="mono" style={{ fontSize: 11, fontWeight: 700, color: 'var(--color-harbor)' }}>
                        {h.fromStatus ? `${h.fromStatus} → ${h.toStatus}` : h.toStatus}
                      </span>
                    </div>
                    {h.reason && (
                      <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 2 }}>
                        {h.reason}
                      </div>
                    )}
                    <div className="mono" style={{ fontSize: 10, color: 'var(--color-ink-2)', marginTop: 2, fontVariantNumeric: 'tabular-nums' }}>
                      {new Date(h.occurredAt).toLocaleTimeString()}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Delivery Attempts Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ padding: '12px 18px', borderBottom: '1px solid var(--color-rail)', backgroundColor: 'var(--color-chalk)' }}>
          <h2 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
            Delivery Attempt Records ({notification.deliveryAttempts.length})
          </h2>
        </div>

        {notification.deliveryAttempts.length === 0 ? (
          <div style={{ color: 'var(--color-ink-2)', fontSize: 12, padding: 24, textAlign: 'center' }}>
            No dispatch attempts recorded yet.
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Attempt</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Provider</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>HTTP Status</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Error Code</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Duration</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Attempted At</th>
                </tr>
              </thead>
              <tbody>
                {notification.deliveryAttempts.map((att) => (
                  <tr key={att.attemptNumber} style={{ borderBottom: '1px solid var(--color-rail)' }}>
                    <td className="mono" style={{ padding: '10px 14px', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>#{att.attemptNumber}</td>
                    <td style={{ padding: '10px 14px', fontSize: 12 }}>{att.provider}</td>
                    <td style={{ padding: '10px 14px' }}>
                      <span
                        className={`chip ${att.status === 'DELIVERED' ? 'chip-delivered' : 'chip-failed'}`}
                        style={{ fontSize: 10, padding: '1px 6px' }}
                      >
                        {att.status}
                      </span>
                    </td>
                    <td className="mono" style={{ padding: '10px 14px', fontSize: 11 }}>{att.httpStatus ?? '—'}</td>
                    <td className="mono" style={{ padding: '10px 14px', fontSize: 11, color: att.errorCode ? 'var(--color-st-failed)' : 'inherit' }}>
                      {att.errorCode ?? '—'}
                    </td>
                    <td className="mono" style={{ padding: '10px 14px', fontSize: 11, fontVariantNumeric: 'tabular-nums' }}>
                      {att.durationMs != null ? `${att.durationMs}ms` : '—'}
                    </td>
                    <td className="mono" style={{ padding: '10px 14px', fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
                      {new Date(att.attemptedAt).toLocaleTimeString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}