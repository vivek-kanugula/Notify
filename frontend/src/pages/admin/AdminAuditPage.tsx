import { useState, useEffect, useCallback, Fragment } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  listAdminAuditLog,
  type AdminAuditLogEntry,
} from '../../api/adminAudit';
import {
  Search,
  Filter,
  ChevronLeft,
  ChevronRight,
  ShieldAlert,
  ChevronDown,
  ChevronUp,
  FileText,
  RotateCcw,
} from 'lucide-react';

export default function AdminAuditPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const [entries, setEntries] = useState<AdminAuditLogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Filters
  const initialTenantId = searchParams.get('tenantId') || '';
  const initialAction = searchParams.get('action') || '';
  const initialPage = parseInt(searchParams.get('page') || '0', 10);

  const [tenantId, setTenantId] = useState(initialTenantId);
  const [action, setAction] = useState(initialAction);
  const [page, setPage] = useState(initialPage);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Expanded rows state
  const [expandedId, setExpandedId] = useState<number | null>(null);

  const fetchAuditLog = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listAdminAuditLog({
        tenantId: tenantId.trim() || undefined,
        action: action.trim() || undefined,
        page,
        size: 20,
      });
      setEntries(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err: any) {
      setError(err.message || 'Failed to list audit log');
    } finally {
      setLoading(false);
    }
  }, [tenantId, action, page]);

  useEffect(() => {
    fetchAuditLog();

    const params = new URLSearchParams();
    if (tenantId) params.set('tenantId', tenantId);
    if (action) params.set('action', action);
    if (page > 0) params.set('page', String(page));
    setSearchParams(params, { replace: true });
  }, [fetchAuditLog, tenantId, action, page, setSearchParams]);

  const toggleExpand = (id: number) => {
    setExpandedId((prev) => (prev === id ? null : id));
  };

  const getActionChipClass = (act: string) => {
    if (act.includes('DELETE') || act.includes('REVOKE')) {
      return 'chip-failed';
    }
    if (act.includes('SUSPEND') || act.includes('DISCARD')) {
      return 'chip-retrying';
    }
    if (act.includes('ACTIVE') || act.includes('REACTIVATE') || act.includes('REPLAY')) {
      return 'chip-delivered';
    }
    return 'chip-processing';
  };

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 4px', color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
            Administrative Audit Log
          </h1>
          <p style={{ fontSize: 13, color: 'var(--color-ink-2)', margin: 0 }}>
            Immutable, append-only security record of all operator actions, parameter mutations, and lifecycle transitions.
          </p>
        </div>
        <div style={{ fontSize: 12, color: 'var(--color-ink-2)', backgroundColor: 'var(--color-chalk)', padding: '6px 12px', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
          Total entries: <strong className="mono" style={{ color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>{totalElements}</strong>
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
            placeholder="Filter by Tenant UUID…"
            value={tenantId}
            onChange={(e) => {
              setTenantId(e.target.value);
              setPage(0);
            }}
            className="input-text mono"
            style={{ border: 'none', padding: '6px 0', fontSize: 12 }}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Filter size={14} style={{ color: 'var(--color-ink-2)' }} />
          <input
            type="text"
            placeholder="Action (e.g. SUSPEND)…"
            value={action}
            onChange={(e) => {
              setAction(e.target.value);
              setPage(0);
            }}
            className="input-text mono"
            style={{
              padding: '5px 10px',
              fontSize: 12,
              minWidth: 160,
              width: 'auto',
            }}
          />

          {(tenantId || action) && (
            <button
              type="button"
              onClick={() => {
                setTenantId('');
                setAction('');
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
            <span style={{ fontSize: 13 }}>Loading audit log…</span>
          </div>
        ) : entries.length === 0 ? (
          <div style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div style={{ width: 36, height: 36, borderRadius: 'var(--radius-control)', background: 'var(--color-chalk)', color: 'var(--color-ink-2)', display: 'grid', placeItems: 'center', margin: '0 auto 12px' }}>
              <FileText size={18} />
            </div>
            <p style={{ fontWeight: 600, color: 'var(--color-harbor)', margin: '0 0 4px' }}>No audit events found</p>
            <p style={{ fontSize: 12, margin: 0 }}>No administrative actions match the active filters.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                  <th style={{ width: 28, padding: '10px 8px' }}></th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Timestamp</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Operator</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Action</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Target</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Tenant</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Reason</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>IP Address</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => {
                  const isExpanded = expandedId === entry.id;
                  return (
                    <Fragment key={entry.id}>
                      <tr
                        onClick={() => toggleExpand(entry.id)}
                        style={{
                          cursor: entry.details ? 'pointer' : 'default',
                          borderBottom: '1px solid var(--color-rail)',
                          backgroundColor: isExpanded ? 'var(--color-chalk)' : 'transparent',
                        }}
                      >
                        <td style={{ padding: '10px 8px', textAlign: 'center' }}>
                          {entry.details ? (
                            isExpanded ? <ChevronUp size={13} color="var(--color-ink-2)" /> : <ChevronDown size={13} color="var(--color-ink-2)" />
                          ) : null}
                        </td>
                        <td className="mono" style={{ padding: '10px 14px', fontSize: 11, color: 'var(--color-ink-2)', whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>
                          {new Date(entry.createdAt).toLocaleDateString()} {new Date(entry.createdAt).toLocaleTimeString()}
                        </td>
                        <td style={{ padding: '10px 14px', fontSize: 12, fontWeight: 500, color: 'var(--color-harbor)' }}>
                          {entry.adminEmail}
                        </td>
                        <td style={{ padding: '10px 14px' }}>
                          <span className={`chip ${getActionChipClass(entry.action)}`} style={{ fontSize: 10, padding: '1px 6px' }}>
                            {entry.action}
                          </span>
                        </td>
                        <td className="mono" style={{ padding: '10px 14px', fontSize: 11, color: 'var(--color-ink-1)' }}>
                          {entry.targetType}: {entry.targetId.slice(0, 8)}…
                        </td>
                        <td style={{ padding: '10px 14px' }}>
                          {entry.tenantSlug ? (
                            <span className="mono" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-signal)' }}>
                              {entry.tenantSlug}
                            </span>
                          ) : entry.tenantId ? (
                            <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
                              {entry.tenantId.slice(0, 8)}…
                            </span>
                          ) : (
                            <span style={{ color: 'var(--color-ink-2)', fontSize: 11 }}>—</span>
                          )}
                        </td>
                        <td style={{ padding: '10px 14px', fontSize: 12, maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={entry.reason || undefined}>
                          {entry.reason || <span style={{ color: 'var(--color-ink-2)' }}>—</span>}
                        </td>
                        <td className="mono" style={{ padding: '10px 14px', fontSize: 11, color: 'var(--color-ink-2)' }}>
                          {entry.ipAddress}
                        </td>
                      </tr>

                      {isExpanded && entry.details && (
                        <tr style={{ borderBottom: '1px solid var(--color-rail)' }}>
                          <td colSpan={8} style={{ backgroundColor: 'var(--color-chalk)', padding: '12px 16px' }}>
                            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)', marginBottom: 6 }}>
                              Structured Event Payload:
                            </div>
                            <pre
                              className="mono"
                              style={{
                                padding: 12,
                                backgroundColor: '#0F172A',
                                color: '#E2E8F0',
                                borderRadius: 'var(--radius-control)',
                                fontSize: 11,
                                overflowX: 'auto',
                                maxHeight: 220,
                                margin: 0,
                                lineHeight: 1.5,
                              }}
                            >
                              {entry.details}
                            </pre>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
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