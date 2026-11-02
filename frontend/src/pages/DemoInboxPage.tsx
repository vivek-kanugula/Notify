import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Link } from 'react-router-dom';
import {
  fetchDemoInbox,
  connectDemoStream,
  type DemoInboxItem,
  type DemoStreamStatus,
} from '../api/demoClient';
import type { Channel } from '../api/notifications';
import { getStatusIcon } from '../components/LifecycleRail';
import ThemeToggle from '../components/ThemeToggle';
import {
  Mail,
  Webhook as WebhookIcon,
  Bell,
  ChevronDown,
  ChevronRight,
  ShieldCheck,
  RefreshCw,
  AlertCircle,
  Play,
  RotateCcw,
} from 'lucide-react';

const MAX_FEED_ITEMS = 100;

function getChannelIcon(channel: Channel, size = 14) {
  switch (channel) {
    case 'EMAIL':
      return <Mail size={size} aria-hidden="true" />;
    case 'WEBHOOK':
      return <WebhookIcon size={size} aria-hidden="true" />;
    case 'IN_APP':
      return <Bell size={size} aria-hidden="true" />;
    default:
      return <Mail size={size} aria-hidden="true" />;
  }
}

function formatRelativeTime(dateStr: string): string {
  try {
    const diffMs = Date.now() - new Date(dateStr).getTime();
    const diffSec = Math.floor(diffMs / 1000);
    if (diffSec < 5) return 'Just now';
    if (diffSec < 60) return `${diffSec}s ago`;
    const diffMin = Math.floor(diffSec / 60);
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHour = Math.floor(diffMin / 60);
    if (diffHour < 24) return `${diffHour}h ago`;
    return `${Math.floor(diffHour / 24)}d ago`;
  } catch {
    return dateStr;
  }
}

export default function DemoInboxPage() {
  const [items, setItems] = useState<DemoInboxItem[]>([]);
  const [bufferedQueue, setBufferedQueue] = useState<DemoInboxItem[]>([]);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [channelFilter, setChannelFilter] = useState<'ALL' | Channel>('ALL');
  const [tenantFilter, setTenantFilter] = useState<string>('ALL');
  const [streamStatus, setStreamStatus] = useState<DemoStreamStatus>('OFFLINE');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Reference to current expandedId to prevent stale closures in SSE callback
  const expandedIdRef = useRef<string | null>(null);
  expandedIdRef.current = expandedId;

  // Flush queued items into the visible items list
  const flushBufferedQueue = useCallback(() => {
    setBufferedQueue((currentQueue) => {
      if (currentQueue.length === 0) return currentQueue;
      setItems((prevItems) => {
        // Prepend new buffered items and cap at MAX_FEED_ITEMS
        const merged = [...currentQueue, ...prevItems];
        return merged.slice(0, MAX_FEED_ITEMS);
      });
      return [];
    });
  }, []);

  // Handle incoming live item
  const handleIncomingItem = useCallback(
    (newItem: DemoInboxItem) => {
      if (expandedIdRef.current !== null) {
        // A row is expanded: buffer incoming item to avoid layout shifts
        setBufferedQueue((prev) => [newItem, ...prev].slice(0, MAX_FEED_ITEMS));
      } else {
        // Prepend immediately to feed and enforce MAX_FEED_ITEMS cap
        setItems((prev) => {
          const deduplicated = prev.filter((i) => i.id !== newItem.id);
          return [newItem, ...deduplicated].slice(0, MAX_FEED_ITEMS);
        });
      }
    },
    []
  );

  // Initial load + stream connection
  useEffect(() => {
    let mounted = true;
    let disconnectStream: (() => void) | null = null;

    setLoading(true);
    setError(null);

    fetchDemoInbox()
      .then((initialItems) => {
        if (!mounted) return;
        setItems(initialItems.slice(0, MAX_FEED_ITEMS));
        setLoading(false);

        // Connect live SSE stream after initial fetch
        disconnectStream = connectDemoStream({
          onItem: handleIncomingItem,
          onStatusChange: (status) => {
            if (mounted) {
              setStreamStatus(status);
            }
          },
        });
      })
      .catch((err) => {
        if (!mounted) return;
        setLoading(false);
        if (err.message === 'DEMO_DISABLED') {
          setError('The public demo sandbox is currently disabled.');
        } else {
          setError('Unable to load demo notifications. Please try again.');
        }
      });

    return () => {
      mounted = false;
      if (disconnectStream) {
        disconnectStream();
      }
    };
  }, [handleIncomingItem]);

  // Toggle row expansion (auto-pause active while a row is expanded)
  function toggleExpand(id: string) {
    if (expandedId === id) {
      // Collapsing: flush any buffered items that arrived while expanded
      setExpandedId(null);
      flushBufferedQueue();
    } else {
      setExpandedId(id);
    }
  }

  // Derive unique tenant slugs from loaded items
  const availableTenants = useMemo(() => {
    const slugs = new Set<string>();
    items.forEach((item) => {
      if (item.tenantSlug) slugs.add(item.tenantSlug);
    });
    return Array.from(slugs);
  }, [items]);

  // Filter visible items
  const filteredItems = useMemo(() => {
    return items.filter((item) => {
      if (channelFilter !== 'ALL' && item.channel !== channelFilter) {
        return false;
      }
      if (tenantFilter !== 'ALL' && item.tenantSlug !== tenantFilter) {
        return false;
      }
      return true;
    });
  }, [items, channelFilter, tenantFilter]);

  const isPaused = expandedId !== null;

  return (
    <div className="demo-page" style={{ minHeight: '100vh', backgroundColor: 'var(--color-chalk)' }}>
      <div className="landing-container" style={{ paddingBottom: 64 }}>
        {/* Navigation Header */}
        <header className="landing-nav">
          <Link to="/" className="landing-logo">
            <div
              style={{
                width: 32,
                height: 32,
                borderRadius: 'var(--radius-control)',
                backgroundColor: 'var(--color-signal)',
                display: 'grid',
                placeItems: 'center',
                fontWeight: 800,
                color: '#FFFFFF',
                fontSize: 16,
                letterSpacing: '-0.02em',
                boxShadow: '0 2px 4px rgba(37, 99, 235, 0.25)',
              }}
            >
              N
            </div>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
              <span>Notify</span>
              <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-ink-3)' }}>
                / Live Demo Inbox
              </span>
            </div>
          </Link>

          <nav style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <Link
              to="/"
              className="btn btn-ghost btn-sm"
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: 'var(--color-ink-2)',
                textDecoration: 'none',
              }}
            >
              Home
            </Link>
            <Link
              to="/login"
              className="btn btn-ghost btn-sm"
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: 'var(--color-ink-2)',
                textDecoration: 'none',
              }}
            >
              Sign In
            </Link>
            <Link
              to="/signup"
              className="btn btn-primary btn-sm"
              style={{
                textDecoration: 'none',
              }}
            >
              Create Account
            </Link>
            <ThemeToggle />
          </nav>
        </header>

        {/* Explanatory Banner */}
        <div style={{ marginTop: 28, marginBottom: 20 }}>
          <h1 style={{ fontSize: 22, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
            Live Demo Sandbox
          </h1>
          <p style={{ fontSize: 13, color: 'var(--color-ink-2)', marginTop: 4 }}>
            Live demo inbox: webhooks and in-app messages sent by every Notify tenant. This page is
            public and shows sandbox data.
          </p>
        </div>

        {/* Stream Control & Filter Bar */}
        <div
          className="panel"
          style={{
            padding: '12px 18px',
            marginBottom: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: 16,
          }}
        >
          {/* Status Indicator & Item Count */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                padding: '4px 10px',
                borderRadius: 'var(--radius-chip)',
                backgroundColor: isPaused
                  ? 'var(--color-st-retrying-bg)'
                  : streamStatus === 'LIVE'
                  ? 'var(--color-st-delivered-bg)'
                  : 'var(--color-subtle)',
                border: '1px solid',
                borderColor: isPaused
                  ? 'var(--color-st-retrying-border)'
                  : streamStatus === 'LIVE'
                  ? 'var(--color-st-delivered-border)'
                  : 'var(--color-rail)',
                fontSize: 12,
                fontWeight: 600,
                color: isPaused
                  ? 'var(--color-st-retrying)'
                  : streamStatus === 'LIVE'
                  ? 'var(--color-st-delivered)'
                  : 'var(--color-ink-2)',
              }}
            >
              {isPaused ? (
                <>
                  <span className="live-dot-paused" aria-hidden="true" />
                  <span>Paused (row expanded)</span>
                  {bufferedQueue.length > 0 && (
                    <span
                      style={{
                        backgroundColor: 'var(--color-st-retrying)',
                        color: '#FFFFFF',
                        borderRadius: 'var(--radius-chip)',
                        padding: '1px 6px',
                        fontSize: 10,
                      }}
                    >
                      +{bufferedQueue.length} new
                    </span>
                  )}
                </>
              ) : streamStatus === 'LIVE' ? (
                <>
                  <span className="live-dot" aria-hidden="true" />
                  <span>LIVE</span>
                </>
              ) : streamStatus === 'RECONNECTING' ? (
                <>
                  <RefreshCw size={12} className="spin-slow" aria-hidden="true" />
                  <span>Reconnecting…</span>
                </>
              ) : (
                <>
                  <span className="live-dot-offline" aria-hidden="true" />
                  <span>Offline</span>
                </>
              )}
            </div>

            {isPaused && bufferedQueue.length > 0 && (
              <button
                type="button"
                onClick={flushBufferedQueue}
                className="btn btn-ghost btn-sm"
                style={{
                  color: 'var(--color-signal)',
                  gap: 4,
                }}
              >
                <Play size={11} aria-hidden="true" />
                <span>Show {bufferedQueue.length} new</span>
              </button>
            )}

            <span style={{ fontSize: 13, color: 'var(--color-ink-3)' }}>
              Showing {filteredItems.length} of {items.length} events (max {MAX_FEED_ITEMS})
            </span>
          </div>

          {/* Filters: Channel Chips & Tenant Select */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            {/* Channel Filter Chips */}
            <div style={{ display: 'inline-flex', gap: 4 }}>
              {(['ALL', 'WEBHOOK', 'IN_APP', 'EMAIL'] as const).map((ch) => (
                <button
                  key={ch}
                  type="button"
                  onClick={() => setChannelFilter(ch)}
                  style={{
                    padding: '4px 10px',
                    fontSize: 12,
                    fontWeight: 600,
                    borderRadius: 'var(--radius-chip)',
                    border: '1px solid',
                    borderColor: channelFilter === ch ? 'var(--color-signal)' : 'var(--color-rail)',
                    backgroundColor: channelFilter === ch ? 'var(--color-signal)' : 'var(--color-paper)',
                    color: channelFilter === ch ? '#FFFFFF' : 'var(--color-ink-2)',
                    cursor: 'pointer',
                    transition: 'all 0.15s ease',
                  }}
                >
                  {ch === 'ALL' ? 'All Channels' : ch === 'IN_APP' ? 'In-App' : ch}
                </button>
              ))}
            </div>

            {/* Tenant Dropdown */}
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <label htmlFor="tenantSelect" style={{ fontSize: 12, fontWeight: 500, color: 'var(--color-ink-3)' }}>
                Tenant:
              </label>
              <select
                id="tenantSelect"
                value={tenantFilter}
                onChange={(e) => setTenantFilter(e.target.value)}
                style={{
                  padding: '4px 8px',
                  fontSize: 12,
                  borderRadius: 'var(--radius-control)',
                  border: '1px solid var(--color-rail)',
                  backgroundColor: 'var(--color-paper)',
                  color: 'var(--color-harbor)',
                  cursor: 'pointer',
                }}
              >
                <option value="ALL">All Tenants</option>
                {availableTenants.map((slug) => (
                  <option key={slug} value={slug}>
                    {slug.toUpperCase()} ({slug})
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>

        {/* Content Table / States */}
        {loading ? (
          <div className="panel" style={{ padding: '48px 20px', textAlign: 'center' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
            <span style={{ color: 'var(--color-ink-3)', fontSize: 13 }}>
              Connecting to live demo feed…
            </span>
          </div>
        ) : error ? (
          <div
            className="panel"
            style={{
              padding: '32px 20px',
              textAlign: 'center',
              borderColor: 'var(--color-st-failed-border)',
              backgroundColor: 'var(--color-st-failed-bg)',
            }}
          >
            <AlertCircle size={32} style={{ color: 'var(--color-st-failed)', margin: '0 auto 8px' }} />
            <h3 style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-st-failed)' }}>
              {error}
            </h3>
            <p style={{ fontSize: 13, color: 'var(--color-ink-2)', marginTop: 4 }}>
              Demo inbox is served only when enabled in server configuration.
            </p>
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="btn btn-secondary"
              style={{
                marginTop: 16,
              }}
            >
              <RotateCcw size={14} />
              Retry Connection
            </button>
          </div>
        ) : filteredItems.length === 0 ? (
          <div className="panel" style={{ padding: '48px 20px', textAlign: 'center' }}>
            <h3 style={{ fontSize: 15, fontWeight: 600, color: 'var(--color-harbor)' }}>
              No demo notifications match these filters.
            </h3>
            <p style={{ fontSize: 13, color: 'var(--color-ink-3)', marginTop: 4 }}>
              Try selecting "All Channels" or "All Tenants" to view recent activity.
            </p>
          </div>
        ) : (
          <div className="table-container">
            <table className="dense-table" aria-label="Live Demo Sandbox notifications">
              <thead>
                <tr>
                  <th style={{ width: 130 }}>Tenant</th>
                  <th style={{ width: 110 }}>Channel</th>
                  <th style={{ width: 180 }}>Recipient</th>
                  <th>Subject</th>
                  <th style={{ width: 130 }}>Status</th>
                  <th style={{ width: 100 }}>Time</th>
                  <th style={{ width: 50, textAlign: 'center' }}>Inspect</th>
                </tr>
              </thead>
              <tbody>
                {filteredItems.map((item) => {
                  const isExpanded = expandedId === item.id;

                  return (
                    <tr
                      key={item.id}
                      onClick={() => toggleExpand(item.id)}
                      style={{
                        cursor: 'pointer',
                        backgroundColor: isExpanded ? 'var(--color-subtle)' : undefined,
                      }}
                    >
                      {/* Tenant Name + Slug */}
                      <td>
                        <div style={{ display: 'flex', flexDirection: 'column' }}>
                          <span style={{ fontWeight: 600, color: 'var(--color-harbor)', fontSize: 13 }}>
                            {item.tenantName || item.tenantSlug || 'Acme Corp'}
                          </span>
                          <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-3)' }}>
                            {item.tenantSlug || 'acme'}
                          </span>
                        </div>
                      </td>

                      {/* Channel Icon + Name */}
                      <td>
                        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                          {getChannelIcon(item.channel, 13)}
                          <span style={{ fontSize: 12, fontWeight: 600 }}>
                            {item.channel === 'IN_APP' ? 'In-App' : item.channel}
                          </span>
                        </div>
                      </td>

                      {/* Masked Recipient */}
                      <td>
                        <span className="mono" style={{ fontSize: 12, color: 'var(--color-harbor)' }}>
                          {item.recipient}
                        </span>
                      </td>

                      {/* Sanitized Subject */}
                      <td>
                        <span
                          style={{
                            fontSize: 13,
                            color: 'var(--color-harbor)',
                            display: '-webkit-box',
                            WebkitLineClamp: 1,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                          }}
                        >
                          {item.subject || '—'}
                        </span>
                      </td>

                      {/* Status Chip */}
                      <td>
                        <span
                          className={`status-chip status-${item.status.toLowerCase()}`}
                          style={{
                            padding: '2px 8px',
                            fontSize: 11,
                          }}
                        >
                          {getStatusIcon(item.status, 11)}
                          <span>{item.status}</span>
                        </span>
                      </td>

                      {/* Relative Timestamp */}
                      <td>
                        <span
                          className="mono tabular-nums"
                          style={{ fontSize: 12, color: 'var(--color-ink-3)' }}
                          title={new Date(item.createdAt).toUTCString()}
                        >
                          {formatRelativeTime(item.createdAt)}
                        </span>
                      </td>

                      {/* Expand Action */}
                      <td style={{ textAlign: 'center' }}>
                        <button
                          type="button"
                          aria-label={isExpanded ? 'Collapse payload' : 'Expand payload'}
                          style={{
                            background: 'none',
                            border: 'none',
                            cursor: 'pointer',
                            color: 'var(--color-ink-3)',
                            display: 'grid',
                            placeItems: 'center',
                            padding: 4,
                          }}
                        >
                          {isExpanded ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            {/* Expandable Safe Payload Drawer if a row is expanded */}
            {expandedId && (
              <div
                style={{
                  borderTop: '1px solid var(--color-rail)',
                  padding: '20px 24px',
                  backgroundColor: 'var(--color-subtle)',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-harbor)' }}>
                    <ShieldCheck size={16} style={{ color: 'var(--color-st-delivered)' }} aria-hidden="true" />
                    <span style={{ fontSize: 13, fontWeight: 700 }}>
                      Safe Public Payload Projection
                    </span>
                  </div>
                  <span style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>
                    Row inspection auto-pauses live injection
                  </span>
                </div>

                {(() => {
                  const target = items.find((i) => i.id === expandedId);
                  if (!target) return null;

                  const safeProjection = {
                    id: target.id,
                    tenantSlug: target.tenantSlug,
                    tenantName: target.tenantName,
                    channel: target.channel,
                    status: target.status,
                    recipient: target.recipient,
                    subject: target.subject,
                    createdAt: target.createdAt,
                    security: {
                      maskedRecipient: true,
                      sanitizedSubject: true,
                      bodyOmitted: true,
                      headersOmitted: true,
                      secretsOmitted: true,
                    },
                  };

                  return (
                    <div>
                      <pre className="code-excerpt" style={{ margin: 0, padding: 14 }}>
                        {JSON.stringify(safeProjection, null, 2)}
                      </pre>
                      <p style={{ fontSize: 12, color: 'var(--color-ink-3)', marginTop: 10 }}>
                        Note: Internal notification bodies, metadata, API keys,
                        and webhook secrets are never exposed on the public demo surface.
                      </p>
                    </div>
                  );
                })()}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
