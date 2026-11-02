import { useState, useEffect, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  ArrowLeft,
  RotateCw,
  Trash2,
  Copy,
  Check,
  ExternalLink,
  Shield,
} from 'lucide-react';
import {
  getAdminDlqEntryDetail,
  type DlqEntryDetail,
} from '../../api/adminDlq';
import LifecycleRail from '../../components/LifecycleRail';
import DlqReplayModal from './DlqReplayModal';
import DlqDiscardModal from './DlqDiscardModal';

export default function AdminDlqDetailPage() {
  const { id } = useParams<{ id: string }>();

  const [detail, setDetail] = useState<DlqEntryDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modals
  const [showReplayModal, setShowReplayModal] = useState(false);
  const [showDiscardModal, setShowDiscardModal] = useState(false);
  const [copiedPayload, setCopiedPayload] = useState(false);

  const fetchDetail = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const data = await getAdminDlqEntryDetail(id);
      setDetail(data);
    } catch (err: any) {
      setError(err?.message || 'Failed to load dead-letter entry details.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  const copyPayload = () => {
    if (!detail?.payloadSnapshot) return;
    navigator.clipboard.writeText(detail.payloadSnapshot);
    setCopiedPayload(true);
    setTimeout(() => setCopiedPayload(false), 2000);
  };

  if (loading) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div className="panel" style={{ textAlign: 'center', padding: '56px 0', color: 'var(--color-ink-2)' }}>
          <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
          <span style={{ fontSize: 13 }}>Loading dead-letter entry details…</span>
        </div>
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="page-container" style={{ maxWidth: 1280 }}>
        <div style={{ marginBottom: 16 }}>
          <Link
            to="/admin/dlq"
            className="btn btn-secondary"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}
          >
            <ArrowLeft size={13} /> Back to Dead-letter Queue
          </Link>
        </div>
        <div className="panel" style={{ padding: 24, borderLeft: '3px solid var(--color-st-failed)' }}>
          <div style={{ color: 'var(--color-st-failed)', fontWeight: 600, fontSize: 14, marginBottom: 4 }}>
            Error loading entry
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>{error || 'Dead-letter entry not found.'}</p>
        </div>
      </div>
    );
  }

  const isEntryOpen = detail.status === 'OPEN';

  let formattedJson = detail.payloadSnapshot;
  try {
    const parsed = JSON.parse(detail.payloadSnapshot);
    formattedJson = JSON.stringify(parsed, null, 2);
  } catch {}

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Back Link */}
      <div style={{ marginBottom: 16 }}>
        <Link
          to="/admin/dlq"
          className="btn btn-secondary"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 12,
            textDecoration: 'none',
          }}
        >
          <ArrowLeft size={13} /> Back to Dead-letter Queue
        </Link>
      </div>

      {/* Header Bar */}
      <div
        className="panel"
        style={{
          padding: 20,
          marginBottom: 20,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          flexWrap: 'wrap',
          gap: 16,
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 6 }}>
            <h1 style={{ fontSize: 20, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
              DLQ Entry
            </h1>
            <span className="mono" style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-signal)' }}>
              {detail.entryId}
            </span>
            <span
              className="mono"
              style={{
                fontSize: 11,
                padding: '2px 8px',
                borderRadius: 'var(--radius-chip)',
                backgroundColor: 'var(--color-chalk)',
                border: '1px solid var(--color-rail)',
                fontWeight: 600,
              }}
            >
              {detail.tenantSlug}
            </span>
            <span
              className="chip"
              style={{
                backgroundColor:
                  detail.status === 'OPEN'
                    ? 'rgba(138, 77, 212, 0.12)'
                    : detail.status === 'RESOLVED'
                    ? 'var(--color-st-delivered-bg)'
                    : 'var(--color-st-cancelled-bg)',
                color:
                  detail.status === 'OPEN'
                    ? 'var(--color-st-dlq)'
                    : detail.status === 'RESOLVED'
                    ? 'var(--color-st-delivered)'
                    : 'var(--color-ink-2)',
                fontSize: 11,
                padding: '2px 8px',
                fontWeight: 700,
              }}
            >
              {detail.status}
            </span>
          </div>

          <p style={{ margin: 0, fontSize: 12, color: 'var(--color-ink-2)' }}>
            Channel: <strong style={{ color: 'var(--color-harbor)' }}>{detail.channel}</strong> • Reason:{' '}
            <strong className="mono" style={{ color: 'var(--color-st-failed)' }}>{detail.reason}</strong> • Attempts:{' '}
            <strong className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{detail.attemptCount}</strong>
          </p>
        </div>

        {/* Action Buttons */}
        <div style={{ display: 'flex', gap: 8 }}>
          {isEntryOpen ? (
            <>
              <button
                type="button"
                onClick={() => setShowReplayModal(true)}
                className="btn btn-primary"
                style={{ fontSize: 12 }}
              >
                <RotateCw size={12} />
                <span>Replay Message</span>
              </button>
              <button
                type="button"
                onClick={() => setShowDiscardModal(true)}
                className="btn btn-danger"
                style={{ fontSize: 12 }}
              >
                <Trash2 size={12} />
                <span>Discard Message</span>
              </button>
            </>
          ) : (
            <div
              style={{
                padding: '6px 12px',
                backgroundColor: 'var(--color-chalk)',
                border: '1px solid var(--color-rail)',
                borderRadius: 'var(--radius-control)',
                fontSize: 11,
                color: 'var(--color-ink-1)',
              }}
            >
              <strong>Resolution:</strong> {detail.status} by <span className="mono">{detail.resolvedBy || 'OPERATOR'}</span>
              {detail.resolvedAt && (
                <span> on {new Date(detail.resolvedAt).toLocaleDateString()}</span>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Resolution Note Banner */}
      {detail.resolutionNote && (
        <div
          className="panel"
          style={{
            padding: 14,
            marginBottom: 20,
            backgroundColor: detail.status === 'RESOLVED' ? 'var(--color-st-delivered-bg)' : 'var(--color-chalk)',
            borderLeft: `3px solid ${detail.status === 'RESOLVED' ? 'var(--color-st-delivered)' : 'var(--color-ink-2)'}`,
          }}
        >
          <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 2 }}>
            Operator Resolution Note
          </div>
          <div style={{ fontSize: 12, color: 'var(--color-ink-1)', lineHeight: 1.5 }}>
            {detail.resolutionNote}
          </div>
        </div>
      )}

      {/* Lifecycle Rail */}
      <div style={{ marginBottom: 20 }}>
        <h2 style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)', margin: '0 0 8px' }}>
          Terminal Delivery Rail
        </h2>
        <LifecycleRail
          status="DLQ"
          variant="full"
          attemptCount={detail.attemptCount}
          maxAttempts={detail.notification?.maxAttempts || detail.attemptCount}
          createdAt={detail.createdAt}
          completedAt={detail.updatedAt}
        />
      </div>

      {/* 2-Column Grid: Attributes and Payload Snapshot */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))',
          gap: 20,
          marginBottom: 20,
        }}
      >
        {/* Entry Attributes Panel */}
        <div className="panel" style={{ padding: 20 }}>
          <h3 style={{ fontSize: 13, fontWeight: 700, margin: '0 0 14px', color: 'var(--color-harbor)' }}>
            Quarantine Attributes
          </h3>

          <div style={{ display: 'grid', gridTemplateColumns: '140px 1fr', rowGap: 10, fontSize: 12 }}>
            <span style={{ color: 'var(--color-ink-2)' }}>Notification:</span>
            <Link
              to={`/admin/notifications/${detail.notificationId}`}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--color-signal)', textDecoration: 'none', fontWeight: 600 }}
            >
              <span className="mono">{detail.notificationId}</span>
              <ExternalLink size={11} />
            </Link>

            <span style={{ color: 'var(--color-ink-2)' }}>Tenant ID:</span>
            <span className="mono" style={{ color: 'var(--color-ink-1)' }}>{detail.tenantId}</span>

            <span style={{ color: 'var(--color-ink-2)' }}>DLQ Reason:</span>
            <span style={{ fontWeight: 600 }}>{detail.reason}</span>

            <span style={{ color: 'var(--color-ink-2)' }}>Last Error Code:</span>
            <span className="mono" style={{ color: 'var(--color-st-failed)', fontWeight: 600 }}>
              {detail.lastErrorCode || '—'}
            </span>

            <span style={{ color: 'var(--color-ink-2)' }}>Attempt Count:</span>
            <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{detail.attemptCount}</span>

            <span style={{ color: 'var(--color-ink-2)' }}>Replay Count:</span>
            <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{detail.replayCount}</span>

            <span style={{ color: 'var(--color-ink-2)' }}>Delivery Cycle:</span>
            <span className="mono">{detail.sourceDeliveryCycle ?? 1}</span>

            <span style={{ color: 'var(--color-ink-2)' }}>Dispatch Generation:</span>
            <span className="mono">{detail.sourceGeneration ?? 1}</span>

            <span style={{ color: 'var(--color-ink-2)' }}>Quarantined At:</span>
            <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }} title={new Date(detail.createdAt).toUTCString()}>
              {new Date(detail.createdAt).toLocaleString()}
            </span>

            <span style={{ color: 'var(--color-ink-2)' }}>Last Updated:</span>
            <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }} title={new Date(detail.updatedAt).toUTCString()}>
              {new Date(detail.updatedAt).toLocaleString()}
            </span>
          </div>
        </div>

        {/* Dead-Letter Envelope Payload Snapshot */}
        <div className="panel" style={{ padding: 20 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <h3 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Envelope Snapshot
              </h3>
              <span style={{ fontSize: 10, padding: '1px 6px', borderRadius: 3, background: 'var(--color-chalk)', border: '1px solid var(--color-rail)', fontWeight: 600 }}>
                INERT JSON
              </span>
            </div>

            <button
              type="button"
              onClick={copyPayload}
              className="btn btn-secondary"
              style={{
                padding: '4px 10px',
                fontSize: 11,
              }}
            >
              {copiedPayload ? <Check size={11} style={{ color: 'var(--color-st-delivered)' }} /> : <Copy size={11} />}
              <span>{copiedPayload ? 'Copied' : 'Copy JSON'}</span>
            </button>
          </div>

          {/* Privacy Note */}
          <div
            style={{
              padding: '8px 10px',
              backgroundColor: 'var(--color-chalk)',
              border: '1px solid var(--color-rail)',
              borderRadius: 'var(--radius-control)',
              fontSize: 11,
              color: 'var(--color-ink-2)',
              marginBottom: 10,
              lineHeight: 1.4,
              display: 'flex',
              alignItems: 'center',
              gap: 6,
            }}
          >
            <Shield size={13} style={{ color: 'var(--color-signal)', flexShrink: 0 }} />
            <span><strong>Security Invariant:</strong> Envelope metadata only. Message body and recipient fields are strictly suppressed.</span>
          </div>

          <pre
            style={{
              backgroundColor: '#0F172A',
              color: '#E2E8F0',
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
              fontSize: 11,
              lineHeight: 1.5,
              padding: 12,
              borderRadius: 'var(--radius-control)',
              overflowX: 'auto',
              maxHeight: 260,
              margin: 0,
            }}
          >
            <code>{formattedJson}</code>
          </pre>
        </div>
      </div>

      {/* Historical Attempts Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden', marginBottom: 20 }}>
        <div
          style={{
            padding: '12px 18px',
            borderBottom: '1px solid var(--color-rail)',
            backgroundColor: 'var(--color-chalk)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <h3 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
            Historical Delivery Attempts
          </h3>
          <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
            {detail.attemptsTruncated
              ? `Showing latest ${detail.attempts.length} of ${detail.attemptTotalCount} attempts`
              : `${detail.attempts.length} attempts recorded`}
          </span>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
            <thead>
              <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                <th style={{ width: 60 }}>Attempt</th>
                <th>Cycle</th>
                <th>Outcome</th>
                <th>Error Code</th>
                <th>HTTP Status</th>
                <th>Provider</th>
                <th style={{ textAlign: 'right' }}>Latency</th>
                <th>Timestamp</th>
              </tr>
            </thead>
            <tbody>
              {!detail.attempts || detail.attempts.length === 0 ? (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', padding: 24, color: 'var(--color-ink-2)', fontSize: 12 }}>
                    No prior delivery attempt records available.
                  </td>
                </tr>
              ) : (
                detail.attempts.map((att) => (
                  <tr key={`${att.attemptNumber}-${att.startedAt}`} style={{ borderBottom: '1px solid var(--color-rail)' }}>
                    <td className="mono" style={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                      #{att.attemptNumber}
                    </td>
                    <td className="mono" style={{ color: 'var(--color-ink-2)' }}>
                      {att.deliveryCycle}
                    </td>
                    <td>
                      <span
                        className={`chip ${att.outcome === 'DELIVERED' ? 'chip-delivered' : 'chip-failed'}`}
                        style={{ fontSize: 10, padding: '1px 6px' }}
                      >
                        {att.outcome}
                      </span>
                    </td>
                    <td>
                      <span className="mono" style={{ fontSize: 11, color: 'var(--color-st-failed)' }}>
                        {att.errorCode || '—'}
                      </span>
                    </td>
                    <td>
                      <span className="mono" style={{ fontSize: 11 }}>
                        {att.httpStatus ?? '—'}
                      </span>
                    </td>
                    <td style={{ fontSize: 12 }}>{att.provider}</td>
                    <td style={{ textAlign: 'right' }} className="mono">
                      <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                        {att.latencyMs != null ? `${att.latencyMs}ms` : '—'}
                      </span>
                    </td>
                    <td>
                      <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }} title={new Date(att.startedAt).toUTCString()}>
                        {new Date(att.startedAt).toLocaleTimeString()}
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Status History Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        <div
          style={{
            padding: '12px 18px',
            borderBottom: '1px solid var(--color-rail)',
            backgroundColor: 'var(--color-chalk)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <h3 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
            Status Transition History
          </h3>
          <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
            {detail.historyTotalCount} transitions
          </span>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
            <thead>
              <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                <th>From</th>
                <th>To</th>
                <th>Actor</th>
                <th>Reason</th>
                <th>Occurred At</th>
              </tr>
            </thead>
            <tbody>
              {!detail.history || detail.history.length === 0 ? (
                <tr>
                  <td colSpan={5} style={{ textAlign: 'center', padding: 24, color: 'var(--color-ink-2)', fontSize: 12 }}>
                    No transition history recorded.
                  </td>
                </tr>
              ) : (
                detail.history.map((hist, idx) => (
                  <tr key={idx} style={{ borderBottom: '1px solid var(--color-rail)' }}>
                    <td>
                      <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
                        {hist.fromStatus || 'INIT'}
                      </span>
                    </td>
                    <td>
                      <strong className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)' }}>
                        {hist.toStatus}
                      </strong>
                    </td>
                    <td>
                      <span className="mono" style={{ fontSize: 11 }}>
                        {hist.actor}
                      </span>
                    </td>
                    <td>
                      <span style={{ fontSize: 11, color: 'var(--color-ink-1)' }}>
                        {hist.reason || '—'}
                      </span>
                    </td>
                    <td>
                      <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }} title={new Date(hist.occurredAt).toUTCString()}>
                        {new Date(hist.occurredAt).toLocaleString()}
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modals */}
      <DlqReplayModal
        entryId={detail.entryId}
        tenantSlug={detail.tenantSlug}
        channel={detail.channel}
        isOpen={showReplayModal}
        onClose={() => setShowReplayModal(false)}
        onSuccess={fetchDetail}
      />

      <DlqDiscardModal
        entryId={detail.entryId}
        tenantSlug={detail.tenantSlug}
        channel={detail.channel}
        isOpen={showDiscardModal}
        onClose={() => setShowDiscardModal(false)}
        onSuccess={fetchDetail}
      />
    </div>
  );
}