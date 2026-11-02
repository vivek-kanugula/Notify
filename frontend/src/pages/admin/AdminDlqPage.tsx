import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams, Link } from 'react-router-dom';
import {
  Archive,
  RefreshCw,
  Mail,
  Webhook,
  Bell,
  Search,
  ChevronRight,
} from 'lucide-react';
import {
  listAdminDlqEntries,
  type DlqEntrySummary,
  type DlqStatus,
  type DlqReason,
  type Channel,
} from '../../api/adminDlq';

export default function AdminDlqPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // Filter state
  const statusParam = searchParams.get('status') || 'ALL';
  const channelParam = searchParams.get('channel') || 'ALL';
  const reasonParam = searchParams.get('reason') || 'ALL';
  const tenantIdParam = searchParams.get('tenantId') || '';
  const cursorParam = searchParams.get('cursor') || '';

  const [entries, setEntries] = useState<DlqEntrySummary[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchEntries = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await listAdminDlqEntries({
        status: statusParam === 'ALL' ? undefined : statusParam,
        channel: channelParam === 'ALL' ? undefined : channelParam,
        reason: reasonParam === 'ALL' ? undefined : reasonParam,
        tenantId: tenantIdParam.trim() ? tenantIdParam.trim() : undefined,
        cursor: cursorParam || undefined,
        limit: 25,
      });
      setEntries(res.items);
      setNextCursor(res.nextCursor);
      setHasMore(res.hasMore);
    } catch (err: any) {
      setError(err?.message || 'Failed to load Dead-Letter Queue entries.');
    } finally {
      setLoading(false);
    }
  }, [statusParam, channelParam, reasonParam, tenantIdParam, cursorParam]);

  useEffect(() => {
    fetchEntries();
  }, [fetchEntries]);

  const updateParam = (key: string, value: string) => {
    const next = new URLSearchParams(searchParams);
    if (!value || value === 'ALL') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    next.delete('cursor'); // Reset pagination on filter change
    setSearchParams(next);
  };

  const getChannelIcon = (ch: Channel) => {
    switch (ch) {
      case 'EMAIL':
        return <Mail size={12} />;
      case 'WEBHOOK':
        return <Webhook size={12} />;
      case 'IN_APP':
        return <Bell size={12} />;
      default:
        return <Archive size={12} />;
    }
  };

  const getStatusBadge = (st: DlqStatus) => {
    switch (st) {
      case 'OPEN':
        return (
          <span
            className="chip chip-retrying"
            style={{
              backgroundColor: 'rgba(138, 77, 212, 0.12)',
              color: 'var(--color-st-dlq)',
              border: '1px solid rgba(138, 77, 212, 0.3)',
              fontSize: 10,
              padding: '1px 7px',
            }}
          >
            Open
          </span>
        );
      case 'RESOLVED':
        return (
          <span
            className="chip chip-delivered"
            style={{ fontSize: 10, padding: '1px 7px' }}
          >
            Resolved
          </span>
        );
      case 'DISCARDED':
        return (
          <span
            className="chip chip-cancelled"
            style={{ fontSize: 10, padding: '1px 7px' }}
          >
            Discarded
          </span>
        );
      default:
        return <span className="chip" style={{ fontSize: 10 }}>{st}</span>;
    }
  };

  const getReasonBadge = (reason: DlqReason) => {
    const isPoison = reason === 'UNRECOVERABLE_FAILURE' || reason === 'INVALID_PAYLOAD';
    return (
      <span
        className="mono"
        style={{
          fontSize: 10,
          fontWeight: 600,
          color: isPoison ? 'var(--color-st-failed)' : 'var(--color-st-retrying)',
          backgroundColor: isPoison ? 'var(--color-st-failed-bg)' : 'var(--color-st-retrying-bg)',
          border: `1px solid ${isPoison ? 'rgba(215, 55, 60, 0.2)' : 'rgba(217, 119, 6, 0.2)'}`,
          padding: '1px 6px',
          borderRadius: 'var(--radius-chip)',
        }}
      >
        {reason}
      </span>
    );
  };

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 16,
          marginBottom: 20,
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
            <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
              Dead-Letter Queue
            </h1>
            <span
              className="mono"
              style={{
                fontSize: 10,
                padding: '2px 8px',
                borderRadius: 'var(--radius-chip)',
                backgroundColor: 'rgba(138, 77, 212, 0.12)',
                color: 'var(--color-st-dlq)',
                fontWeight: 700,
                border: '1px solid rgba(138, 77, 212, 0.25)',
              }}
            >
              Zero-Leak Compliant
            </span>
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>
            Quarantined messages, failure telemetry, replay diagnostics, and operator discard workflows.
          </p>
        </div>

        <button
          type="button"
          onClick={fetchEntries}
          disabled={loading}
          className="btn btn-secondary"
          style={{ fontSize: 12, padding: '6px 12px' }}
        >
          <RefreshCw size={12} className={loading ? 'spin-slow' : ''} />
          <span>Refresh</span>
        </button>
      </div>

      {/* Filter Bar */}
      <div
        className="panel"
        style={{
          padding: '10px 14px',
          marginBottom: 16,
          display: 'flex',
          flexWrap: 'wrap',
          gap: 12,
          alignItems: 'center',
        }}
      >
        {/* Status Filter */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <label htmlFor="dlq-status-filter" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)' }}>
            Status:
          </label>
          <select
            id="dlq-status-filter"
            value={statusParam}
            onChange={(e) => updateParam('status', e.target.value)}
            className="select-control"
            style={{ fontSize: 12, padding: '4px 8px', width: 'auto' }}
          >
            <option value="ALL">All Statuses</option>
            <option value="OPEN">Open (Pending)</option>
            <option value="RESOLVED">Resolved</option>
            <option value="DISCARDED">Discarded</option>
          </select>
        </div>

        {/* Channel Filter */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <label htmlFor="dlq-channel-filter" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)' }}>
            Channel:
          </label>
          <select
            id="dlq-channel-filter"
            value={channelParam}
            onChange={(e) => updateParam('channel', e.target.value)}
            className="select-control"
            style={{ fontSize: 12, padding: '4px 8px', width: 'auto' }}
          >
            <option value="ALL">All Channels</option>
            <option value="EMAIL">Email</option>
            <option value="WEBHOOK">Webhook</option>
            <option value="IN_APP">In-App</option>
          </select>
        </div>

        {/* Reason Filter */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <label htmlFor="dlq-reason-filter" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)' }}>
            Reason:
          </label>
          <select
            id="dlq-reason-filter"
            value={reasonParam}
            onChange={(e) => updateParam('reason', e.target.value)}
            className="select-control"
            style={{ fontSize: 12, padding: '4px 8px', width: 'auto' }}
          >
            <option value="ALL">All Reasons</option>
            <option value="EXHAUSTED">EXHAUSTED</option>
            <option value="UNRECOVERABLE_FAILURE">UNRECOVERABLE_FAILURE</option>
            <option value="INVALID_PAYLOAD">INVALID_PAYLOAD</option>
            <option value="RATE_LIMIT_EXCEEDED">RATE_LIMIT_EXCEEDED</option>
          </select>
        </div>

        {/* Tenant Search */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 200 }}>
          <div style={{ position: 'relative', width: '100%' }}>
            <Search
              size={13}
              style={{
                position: 'absolute',
                left: 10,
                top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--color-ink-2)',
              }}
            />
            <input
              type="text"
              placeholder="Filter by Tenant UUID…"
              value={tenantIdParam}
              onChange={(e) => updateParam('tenantId', e.target.value)}
              className="input-text mono"
              style={{
                paddingLeft: 28,
                paddingRight: 10,
                paddingTop: 5,
                paddingBottom: 5,
                fontSize: 12,
              }}
            />
          </div>
        </div>
      </div>

      {error && (
        <div
          style={{
            padding: '10px 14px',
            backgroundColor: 'var(--color-st-failed-bg)',
            border: '1px solid var(--color-st-failed)',
            borderRadius: 'var(--radius-control)',
            color: 'var(--color-st-failed)',
            fontSize: 12,
            marginBottom: 20,
          }}
        >
          {error}
        </div>
      )}

      {/* DLQ Data Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ overflowX: 'auto' }}>
          <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
            <thead>
              <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                <th style={{ width: 140, padding: '10px 14px', fontWeight: 600 }}>Entry ID</th>
                <th style={{ padding: '10px 14px', fontWeight: 600 }}>Tenant</th>
                <th style={{ padding: '10px 14px', fontWeight: 600 }}>Channel</th>
                <th style={{ padding: '10px 14px', fontWeight: 600 }}>Reason</th>
                <th style={{ padding: '10px 14px', fontWeight: 600 }}>Error Code</th>
                <th style={{ textAlign: 'center', padding: '10px 14px', fontWeight: 600 }}>Attempts</th>
                <th style={{ textAlign: 'center', padding: '10px 14px', fontWeight: 600 }}>Replays</th>
                <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                <th style={{ padding: '10px 14px', fontWeight: 600 }}>Quarantined At</th>
                <th style={{ width: 80, textAlign: 'right', padding: '10px 14px', fontWeight: 600 }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={10} style={{ textAlign: 'center', padding: 48, color: 'var(--color-ink-2)' }}>
                    <div className="loading-spinner" style={{ margin: '0 auto 10px' }} />
                    <span style={{ fontSize: 13 }}>Loading dead-letter entries…</span>
                  </td>
                </tr>
              ) : entries.length === 0 ? (
                <tr>
                  <td colSpan={10} style={{ textAlign: 'center', padding: 48, color: 'var(--color-ink-2)' }}>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                      <div style={{ width: 36, height: 36, borderRadius: 'var(--radius-control)', background: 'var(--color-chalk)', display: 'grid', placeItems: 'center' }}>
                        <Archive size={18} style={{ color: 'var(--color-ink-2)' }} />
                      </div>
                      <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-harbor)' }}>
                        No dead-letter notifications found
                      </span>
                      <span style={{ fontSize: 12 }}>
                        No quarantined records match the current status, channel, and reason filters.
                      </span>
                    </div>
                  </td>
                </tr>
              ) : (
                entries.map((entry) => (
                  <tr key={entry.entryId} style={{ borderBottom: '1px solid var(--color-rail)' }}>
                    <td style={{ padding: '10px 14px' }}>
                      <span
                        className="mono"
                        style={{ fontSize: 11, color: 'var(--color-harbor)', fontWeight: 600 }}
                        title={entry.entryId}
                      >
                        {entry.entryId.substring(0, 8)}…
                      </span>
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <Link
                        to={`/admin/tenants/${entry.tenantId}`}
                        style={{ textDecoration: 'none' }}
                      >
                        <span className="mono" style={{ fontSize: 11, color: 'var(--color-signal)', fontWeight: 600 }}>
                          {entry.tenantSlug}
                        </span>
                      </Link>
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11 }}>
                        {getChannelIcon(entry.channel)}
                        <span>{entry.channel}</span>
                      </div>
                    </td>
                    <td style={{ padding: '10px 14px' }}>{getReasonBadge(entry.reason)}</td>
                    <td style={{ padding: '10px 14px' }}>
                      <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-1)' }}>
                        {entry.lastErrorCode || '—'}
                      </span>
                    </td>
                    <td style={{ textAlign: 'center', padding: '10px 14px' }}>
                      <strong className="mono" style={{ fontSize: 11, fontVariantNumeric: 'tabular-nums' }}>
                        {entry.attemptCount}
                      </strong>
                    </td>
                    <td style={{ textAlign: 'center', padding: '10px 14px' }}>
                      <span className="mono" style={{ fontSize: 11, fontVariantNumeric: 'tabular-nums', color: entry.replayCount > 0 ? 'var(--color-signal)' : 'var(--color-ink-2)' }}>
                        {entry.replayCount}
                      </span>
                    </td>
                    <td style={{ padding: '10px 14px' }}>{getStatusBadge(entry.status)}</td>
                    <td style={{ padding: '10px 14px' }}>
                      <span
                        className="mono"
                        style={{ fontSize: 11, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}
                        title={new Date(entry.createdAt).toUTCString()}
                      >
                        {new Date(entry.createdAt).toLocaleDateString()} {new Date(entry.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      </span>
                    </td>
                    <td style={{ textAlign: 'right', padding: '10px 14px' }}>
                      <button
                        type="button"
                        onClick={() => navigate(`/admin/dlq/${entry.entryId}`)}
                        className="btn btn-secondary"
                        style={{
                          padding: '3px 8px',
                          fontSize: 11,
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: 4,
                        }}
                      >
                        <span>Inspect</span>
                        <ChevronRight size={11} />
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Footer / Pagination */}
        <div
          style={{
            padding: '10px 18px',
            borderTop: '1px solid var(--color-rail)',
            backgroundColor: 'var(--color-chalk)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span style={{ fontSize: 12, color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
            Showing <strong>{entries.length}</strong> entries
          </span>

          {hasMore && nextCursor && (
            <button
              type="button"
              onClick={() => updateParam('cursor', nextCursor)}
              disabled={loading}
              className="btn btn-secondary"
              style={{ fontSize: 11, padding: '4px 10px' }}
            >
              Next Page →
            </button>
          )}
        </div>
      </div>
    </div>
  );
}