import React, { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  getNotification,
  getNotificationHistory,
  cancelNotification,
  type NotificationDetail,
  type NotificationHistoryEntry,
} from '../api/notifications';
import LifecycleRail, { getStatusIcon } from '../components/LifecycleRail';
import SandboxedIframe from '../components/SandboxedIframe';
import { getChannelIcon } from '../components/FilterBar';
import {
  ArrowLeft,
  Copy,
  Check,
  Ban,
  AlertTriangle,
  X,
} from 'lucide-react';

export default function NotificationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<NotificationDetail | null>(null);
  const [history, setHistory] = useState<NotificationHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Cancellation modal state
  const [showCancelModal, setShowCancelModal] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  // Copy feedback state
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const fetchDetailAndHistory = async (notificationId: string) => {
    try {
      setLoading(true);
      setError(null);
      const [detailData, historyData] = await Promise.all([
        getNotification(notificationId),
        getNotificationHistory(notificationId),
      ]);
      setDetail(detailData);
      setHistory(historyData);
    } catch (err: any) {
      setError(err.message || 'Failed to load notification details');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (id) {
      fetchDetailAndHistory(id);
    }
  }, [id]);

  const copyToClipboard = async (text: string, keyName: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKey(keyName);
      setTimeout(() => {
        setCopiedKey(null);
      }, 2000);
    } catch {
      // Fallback for non-secure origins
    }
  };

  const handleCancelSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!detail) return;

    try {
      setCancelling(true);
      setCancelError(null);
      await cancelNotification(detail.id, cancelReason.trim() || undefined);
      setShowCancelModal(false);
      setCancelReason('');
      setToastMessage('Notification successfully cancelled.');
      await fetchDetailAndHistory(detail.id);
    } catch (err: any) {
      // Check for 409 INVALID_STATE_TRANSITION race condition
      const msg = err.message || '';
      if (msg.includes('409') || msg.includes('INVALID_STATE_TRANSITION')) {
        setCancelError('This notification already started processing and can no longer be cancelled.');
        fetchDetailAndHistory(detail.id);
      } else {
        setCancelError(err.message || 'Failed to cancel notification');
      }
    } finally {
      setCancelling(false);
    }
  };

  if (loading) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div style={{ marginBottom: 20 }}>
          <Link
            to="/app/notifications"
            className="btn btn-ghost btn-sm"
            style={{
              padding: 0,
              gap: 6,
              color: 'var(--color-signal)',
            }}
          >
            <ArrowLeft size={14} /> Back to Notifications
          </Link>
        </div>
        <div className="panel" style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-3)' }}>
          <div className="loading-spinner" style={{ margin: '0 auto 16px' }} />
          Loading notification {id}…
        </div>
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div style={{ marginBottom: 20 }}>
          <Link
            to="/app/notifications"
            className="btn btn-ghost btn-sm"
            style={{
              padding: 0,
              gap: 6,
              color: 'var(--color-signal)',
            }}
          >
            <ArrowLeft size={14} /> Back to Notifications
          </Link>
        </div>
        <div
          className="panel"
          style={{
            padding: 32,
            backgroundColor: 'var(--color-st-failed-bg)',
            borderColor: 'var(--color-st-failed-border)',
            color: 'var(--color-st-failed)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
            <AlertTriangle size={20} />
            <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'inherit' }}>
              Error Loading Notification
            </h2>
          </div>
          <p style={{ fontSize: 13, marginBottom: 16 }}>{error || 'The requested notification could not be found.'}</p>
          <button
            type="button"
            onClick={() => id && fetchDetailAndHistory(id)}
            className="btn btn-secondary btn-sm"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  const isEligibleCancel = detail.status === 'SCHEDULED' || detail.status === 'QUEUED';

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Top Back Nav & Quick Actions */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Link
          to="/app/notifications"
          className="btn btn-ghost btn-sm"
          style={{
            padding: 0,
            gap: 6,
            color: 'var(--color-signal)',
          }}
        >
          <ArrowLeft size={14} /> Back to Notifications
        </Link>

        {isEligibleCancel && (
          <button
            type="button"
            onClick={() => {
              setShowCancelModal(true);
              setCancelError(null);
            }}
            className="btn btn-danger btn-sm"
          >
            <Ban size={14} />
            Cancel Notification
          </button>
        )}
      </div>

      {/* Toast Feedback */}
      {toastMessage && (
        <div
          className="panel"
          style={{
            padding: '10px 16px',
            marginBottom: 16,
            backgroundColor: 'var(--color-st-delivered-bg)',
            borderColor: 'var(--color-st-delivered-border)',
            color: 'var(--color-st-delivered)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span style={{ fontSize: 13, fontWeight: 500 }}>{toastMessage}</span>
          <button
            type="button"
            onClick={() => setToastMessage(null)}
            className="btn btn-ghost btn-icon"
            style={{ color: 'inherit' }}
          >
            <X size={14} />
          </button>
        </div>
      )}

      {/* Header Panel */}
      <div
        className="panel"
        style={{
          padding: '20px 24px',
          marginBottom: 20,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          flexWrap: 'wrap',
          gap: 16,
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8, flexWrap: 'wrap' }}>
            <span
              className={`status-chip status-${detail.status.toLowerCase()}`}
              style={{
                padding: '4px 10px',
                fontSize: 12,
              }}
            >
              {getStatusIcon(detail.status, 13)}
              <span>{detail.status}</span>
            </span>

            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 5,
                fontSize: 12,
                fontWeight: 600,
                color: 'var(--color-ink-2)',
                backgroundColor: 'var(--color-subtle)',
                padding: '4px 10px',
                borderRadius: 'var(--radius-chip)',
                border: '1px solid var(--color-rail)',
              }}
            >
              {getChannelIcon(detail.channel, 13)}
              <span>{detail.channel}</span>
            </span>

            <span className="mono tabular-nums" style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>
              Created: {new Date(detail.createdAt).toLocaleString()}
            </span>
          </div>

          <h1
            style={{
              fontSize: 20,
              fontWeight: 700,
              letterSpacing: '-0.02em',
              color: 'var(--color-harbor)',
              wordBreak: 'break-all',
              marginBottom: 4,
            }}
          >
            {detail.recipient}
          </h1>

          {detail.subject && (
            <p style={{ fontSize: 14, color: 'var(--color-ink-2)', fontWeight: 500 }}>
              Subject: <strong style={{ color: 'var(--color-harbor)' }}>{detail.subject}</strong>
            </p>
          )}
        </div>

        {/* DLQ Notice */}
        {detail.status === 'DLQ' && (
          <div
            style={{
              padding: '10px 14px',
              backgroundColor: 'var(--color-st-dlq-bg)',
              border: '1px solid var(--color-st-dlq-border)',
              borderRadius: 'var(--radius-control)',
              maxWidth: 360,
              fontSize: 12,
              color: 'var(--color-st-dlq)',
            }}
          >
            <strong>Dead-Letter Notice:</strong> Delivery failed after maximum retry attempts. This item requires operator inspection or admin replay.
          </div>
        )}
      </div>

      {/* The Signature Lifecycle Rail (Full Variant) */}
      <LifecycleRail
        status={detail.status}
        scheduledAt={detail.scheduledAt}
        queuedAt={detail.queuedAt}
        processingStartedAt={detail.processingStartedAt}
        completedAt={detail.completedAt}
        createdAt={detail.createdAt}
        attemptCount={detail.attemptCount}
        maxAttempts={detail.maxAttempts}
        history={history}
        variant="full"
      />

      {/* Main Grid: Content (Left) + Metadata Rail (Right 320px) */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) 320px',
          gap: 20,
          alignItems: 'start',
        }}
        className="detail-grid"
      >
        {/* Left Column: Message Content & Tables */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          {/* Message Body Panel */}
          <div className="panel" style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
                Message Content ({detail.channel})
              </h2>
              <button
                type="button"
                onClick={() => copyToClipboard(detail.body, 'body')}
                className="btn btn-ghost btn-sm"
                style={{
                  color: 'var(--color-signal)',
                  gap: 4,
                }}
              >
                {copiedKey === 'body' ? <Check size={13} /> : <Copy size={13} />}
                <span>{copiedKey === 'body' ? 'Copied' : 'Copy Payload'}</span>
              </button>
            </div>

            {detail.channel === 'EMAIL' ? (
              <SandboxedIframe content={detail.body} minHeight={260} />
            ) : (
              <pre
                className="code-excerpt"
                style={{
                  padding: 14,
                  margin: 0,
                }}
              >
                {detail.body}
              </pre>
            )}
          </div>

          {/* Delivery Attempts & Diagnostics Panel */}
          <div className="panel" style={{ padding: 20 }}>
            <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 12 }}>
              Delivery Diagnostics & Attempt Telemetry
            </h2>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                gap: 12,
                backgroundColor: 'var(--color-subtle)',
                padding: 14,
                borderRadius: 'var(--radius-control)',
                border: '1px solid var(--color-rail)',
              }}
            >
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Attempts Budget
                </span>
                <span className="mono tabular-nums" style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
                  {detail.attemptCount} / {detail.maxAttempts}
                </span>
              </div>
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Active Provider
                </span>
                <span className="mono" style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
                  {detail.provider || 'None / Outbox'}
                </span>
              </div>
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Dispatch Generation
                </span>
                <span className="mono tabular-nums" style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
                  gen #{detail.dispatchGeneration}
                </span>
              </div>
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Delivery Cycle
                </span>
                <span className="mono tabular-nums" style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
                  cycle #{detail.deliveryCycle}
                </span>
              </div>
              {detail.lastErrorCode && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <span style={{ fontSize: 11, color: 'var(--color-st-failed)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                    Last Error Code
                  </span>
                  <span
                    className="mono"
                    style={{
                      fontSize: 12,
                      fontWeight: 700,
                      color: 'var(--color-st-failed)',
                      backgroundColor: 'var(--color-st-failed-bg)',
                      padding: '2px 8px',
                      borderRadius: 4,
                      border: '1px solid var(--color-st-failed-border)',
                      display: 'inline-block',
                    }}
                  >
                    {detail.lastErrorCode}
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Immutable Status History Audit Trail Table */}
          <div className="panel" style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <div>
                <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)', margin: 0 }}>
                  Immutable Audit Trail
                </h2>
                <span style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>
                  PostgreSQL append-only status transitions backed by checked conditional predicates
                </span>
              </div>
              <span className="mono tabular-nums" style={{ fontSize: 11, color: 'var(--color-ink-3)' }}>
                {history.length} transition{history.length === 1 ? '' : 's'}
              </span>
            </div>

            {history.length === 0 ? (
              <div style={{ padding: 24, textAlign: 'center', color: 'var(--color-ink-3)', fontSize: 13 }}>
                No status transitions recorded yet.
              </div>
            ) : (
              <div className="table-container">
                <table className="dense-table">
                  <thead>
                    <tr>
                      <th>Transition</th>
                      <th>Actor</th>
                      <th>Reason</th>
                      <th>Timestamp</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.map((h) => {
                      return (
                        <tr key={h.id}>
                          <td>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                              {h.fromStatus ? (
                                <span className={`status-chip status-${h.fromStatus.toLowerCase()}`} style={{ fontSize: 10, padding: '1px 6px' }}>
                                  {h.fromStatus}
                                </span>
                              ) : (
                                <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>—</span>
                              )}
                              <span style={{ color: 'var(--color-rail-strong)' }}>→</span>
                              <span className={`status-chip status-${h.toStatus.toLowerCase()}`} style={{ fontSize: 10, padding: '1px 6px' }}>
                                {getStatusIcon(h.toStatus, 10)}
                                <span>{h.toStatus}</span>
                              </span>
                            </div>
                          </td>
                          <td>
                            <span className="badge-mono">{h.actor}</span>
                          </td>
                          <td style={{ color: h.reason ? 'var(--color-harbor)' : 'var(--color-ink-3)' }}>
                            {h.reason || '—'}
                          </td>
                          <td style={{ color: 'var(--color-ink-3)' }}>
                            <span className="mono tabular-nums">{new Date(h.occurredAt).toLocaleString()}</span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>

        {/* Right Column: Metadata Sidebar Rail (320px) */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <div className="panel" style={{ padding: 20 }}>
            <h2 style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 14 }}>
              Metadata & Identifiers
            </h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 14, fontSize: 13 }}>
              {/* Notification ID */}
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Notification ID
                </span>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                  <span className="mono" style={{ fontSize: 12, wordBreak: 'break-all', color: 'var(--color-harbor)' }}>{detail.id}</span>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(detail.id, 'id')}
                    title="Copy Notification ID"
                    className="btn btn-ghost btn-icon"
                    style={{ color: 'var(--color-signal)' }}
                  >
                    {copiedKey === 'id' ? <Check size={13} /> : <Copy size={13} />}
                  </button>
                </div>
              </div>

              {/* Tenant ID */}
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Tenant ID
                </span>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                  <span className="mono" style={{ fontSize: 12, wordBreak: 'break-all', color: 'var(--color-harbor)' }}>{detail.tenantId}</span>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(detail.tenantId, 'tenantId')}
                    title="Copy Tenant ID"
                    className="btn btn-ghost btn-icon"
                    style={{ color: 'var(--color-signal)' }}
                  >
                    {copiedKey === 'tenantId' ? <Check size={13} /> : <Copy size={13} />}
                  </button>
                </div>
              </div>

              {/* Idempotency Key */}
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Idempotency Key
                </span>
                {detail.idempotencyKey ? (
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span className="mono" style={{ fontSize: 12, wordBreak: 'break-all', color: 'var(--color-harbor)' }}>{detail.idempotencyKey}</span>
                    <button
                      type="button"
                      onClick={() => copyToClipboard(detail.idempotencyKey!, 'idem')}
                      title="Copy Idempotency Key"
                      className="btn btn-ghost btn-icon"
                      style={{ color: 'var(--color-signal)' }}
                    >
                      {copiedKey === 'idem' ? <Check size={13} /> : <Copy size={13} />}
                    </button>
                  </div>
                ) : (
                  <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>None (Unkeyed dispatch)</span>
                )}
              </div>

              {/* Template Version */}
              <div>
                <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 2 }}>
                  Template Version ID
                </span>
                {detail.templateVersionId ? (
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span className="mono" style={{ fontSize: 12, wordBreak: 'break-all', color: 'var(--color-harbor)' }}>{detail.templateVersionId}</span>
                    <button
                      type="button"
                      onClick={() => copyToClipboard(detail.templateVersionId!, 'tpl')}
                      title="Copy Template Version ID"
                      className="btn btn-ghost btn-icon"
                      style={{ color: 'var(--color-signal)' }}
                    >
                      {copiedKey === 'tpl' ? <Check size={13} /> : <Copy size={13} />}
                    </button>
                  </div>
                ) : (
                  <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>Custom Payload</span>
                )}
              </div>

              {/* Timestamps */}
              <div style={{ borderTop: '1px solid var(--color-rail)', paddingTop: 12, display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>Queued At:</span>
                  <span className="mono tabular-nums" style={{ fontSize: 12 }}>
                    {detail.queuedAt ? new Date(detail.queuedAt).toLocaleTimeString() : '—'}
                  </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>Scheduled For:</span>
                  <span className="mono tabular-nums" style={{ fontSize: 12 }}>
                    {detail.scheduledAt ? new Date(detail.scheduledAt).toLocaleTimeString() : '—'}
                  </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>Processing Started:</span>
                  <span className="mono tabular-nums" style={{ fontSize: 12 }}>
                    {detail.processingStartedAt ? new Date(detail.processingStartedAt).toLocaleTimeString() : '—'}
                  </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>Completed At:</span>
                  <span className="mono tabular-nums" style={{ fontSize: 12 }}>
                    {detail.completedAt ? new Date(detail.completedAt).toLocaleTimeString() : '—'}
                  </span>
                </div>
              </div>

              {/* Metadata JSON */}
              {detail.metadata && Object.keys(detail.metadata).length > 0 && (
                <div style={{ borderTop: '1px solid var(--color-rail)', paddingTop: 12 }}>
                  <span style={{ fontSize: 11, color: 'var(--color-ink-3)', fontWeight: 600, display: 'block', marginBottom: 4 }}>
                    Metadata Attributes
                  </span>
                  <pre
                    className="code-excerpt"
                    style={{
                      fontSize: 11,
                      padding: 8,
                      maxHeight: 180,
                    }}
                  >
                    {JSON.stringify(detail.metadata, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Cancellation Confirmation Dialog */}
      {showCancelModal && (
        <div className="modal-overlay">
          <div className="modal-card" style={{ maxWidth: 440 }}>
            <div className="modal-header">
              <h3 style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-harbor)' }}>
                Confirm Cancellation
              </h3>
              <button
                type="button"
                onClick={() => setShowCancelModal(false)}
                className="btn btn-ghost btn-icon"
              >
                <X size={15} />
              </button>
            </div>

            <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.5, marginBottom: 16 }}>
              Cancel this notification? It has not started processing, so nothing will be dispatched.
            </p>

            {cancelError && (
              <div
                style={{
                  padding: '8px 12px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  border: '1px solid var(--color-st-failed-border)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                  marginBottom: 12,
                }}
              >
                {cancelError}
              </div>
            )}

            <form onSubmit={handleCancelSubmit}>
              <div className="form-group" style={{ marginBottom: 16 }}>
                <label>Cancellation Reason (Optional audit record):</label>
                <input
                  type="text"
                  value={cancelReason}
                  onChange={(e) => setCancelReason(e.target.value)}
                  placeholder="e.g. User opted out"
                />
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  onClick={() => setShowCancelModal(false)}
                  className="btn btn-secondary"
                >
                  Keep Active
                </button>
                <button
                  type="submit"
                  disabled={cancelling}
                  className="btn btn-danger"
                >
                  {cancelling ? 'Cancelling…' : 'Confirm Cancel'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
