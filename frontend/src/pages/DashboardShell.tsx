import { useState, useEffect } from 'react';
import { NavLink, Routes, Route, Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { getUsageSummary, getUsageTimeseries, type UsageSummary, type UsageTimeseries } from '../api/usage';
import { listNotifications, type NotificationSummary } from '../api/notifications';
import ApiKeysPage from './ApiKeysPage';
import NotificationsPage from './NotificationsPage';
import CreateNotificationPage from './CreateNotificationPage';
import NotificationDetailPage from './NotificationDetailPage';
import TemplatesPage from './TemplatesPage';
import TemplateDetailPage from './TemplateDetailPage';
import WebhooksPage from './WebhooksPage';
import SettingsPage from './SettingsPage';
import InboxPage from './InboxPage';
import KpiStrip from '../components/KpiStrip';
import ChartPanel from '../components/ChartPanel';
import TenantStatusBanner from '../components/TenantStatusBanner';
import LifecycleRail from '../components/LifecycleRail';
import ThemeToggle from '../components/ThemeToggle';
import { getChannelIcon } from '../components/FilterBar';
import {
  Copy,
  Check,
  Plus,
  ExternalLink,
  Key,
  LayoutDashboard,
  Bell,
  FileText,
  Webhook,
  Inbox,
  Settings,
  ShieldCheck,
  Zap,
} from 'lucide-react';

interface HealthState {
  liveness: string;
  readiness: string;
  loading: boolean;
}

function OverviewContent() {
  const [timeRange, setTimeRange] = useState<'24h' | '7d' | '30d'>('7d');
  const [summary, setSummary] = useState<UsageSummary | null>(null);
  const [timeseries, setTimeseries] = useState<UsageTimeseries | null>(null);
  const [recentNotifications, setRecentNotifications] = useState<NotificationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [copiedCurl, setCopiedCurl] = useState(false);

  useEffect(() => {
    let mounted = true;
    async function loadData() {
      setLoading(true);
      try {
        const now = new Date();
        const fromDate = new Date();
        if (timeRange === '24h') {
          fromDate.setHours(fromDate.getHours() - 24);
        } else if (timeRange === '7d') {
          fromDate.setDate(fromDate.getDate() - 7);
        } else {
          fromDate.setDate(fromDate.getDate() - 30);
        }

        const [sum, ts, notifs] = await Promise.all([
          getUsageSummary({
            from: fromDate.toISOString(),
            to: now.toISOString(),
          }).catch(() => null),
          getUsageTimeseries({
            from: fromDate.toISOString(),
            to: now.toISOString(),
            interval: timeRange === '24h' ? 'hour' : 'day',
          }).catch(() => null),
          listNotifications({ size: 10 }).catch(() => ({ content: [] as NotificationSummary[] })),
        ]);

        if (mounted) {
          setSummary(sum);
          setTimeseries(ts);
          setRecentNotifications(notifs.content || []);
        }
      } finally {
        if (mounted) setLoading(false);
      }
    }
    loadData();
    return () => {
      mounted = false;
    };
  }, [timeRange]);

  const curlExample = `curl -X POST http://localhost:8080/api/v1/notifications \\
  -H "Authorization: Bearer ntf_your_api_key_here" \\
  -H "Content-Type: application/json" \\
  -d '{
    "recipient": "user@example.com",
    "channel": "EMAIL",
    "subject": "Hello from Notify",
    "body": "Welcome to reliable notifications."
  }'`;

  const handleCopyCurl = async () => {
    try {
      await navigator.clipboard.writeText(curlExample);
      setCopiedCurl(true);
      setTimeout(() => setCopiedCurl(false), 2000);
    } catch {
      // Fallback for non-secure origins
    }
  };

  const isZeroState = !loading && (summary?.totalAccepted ?? 0) === 0 && recentNotifications.length === 0;

  return (
    <div className="page-container" style={{ maxWidth: 1240 }}>
      {/* Overview Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 16, marginBottom: 24 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)', marginBottom: 4 }}>
            Tenant Platform Overview
          </h1>
          <p style={{ color: 'var(--color-ink-2)', fontSize: 13 }}>
            Real-time delivery performance telemetry, outbound dispatch analytics, and system audit history.
          </p>
        </div>

        <div style={{ display: 'flex', gap: 10 }}>
          <Link
            to="/app/notifications/new"
            className="btn btn-primary"
            style={{
              textDecoration: 'none',
              fontSize: 13,
            }}
          >
            <Plus size={15} />
            Send Notification
          </Link>
        </div>
      </div>

      {/* Ruled Continuous KPI Strip */}
      <KpiStrip
        summary={summary}
        loading={loading}
        timeRange={timeRange}
        onTimeRangeChange={setTimeRange}
      />

      {/* Deliveries Over Time Chart Panel */}
      <ChartPanel
        timeseries={timeseries}
        summary={summary}
        loading={loading}
      />

      {/* Onboarding Empty State if 0 notifications */}
      {isZeroState && (
        <div
          className="panel"
          style={{
            padding: 32,
            marginBottom: 24,
            backgroundColor: 'var(--color-subtle)',
            border: '1px dashed var(--color-signal)',
          }}
        >
          <div style={{ maxWidth: 680 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-signal)', marginBottom: 8 }}>
              <Zap size={18} />
              <span style={{ fontSize: 13, fontWeight: 700 }}>Quick Start</span>
            </div>
            <h3 style={{ fontSize: 18, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 8 }}>
              Welcome to Notify! Dispatch your first message.
            </h3>
            <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.6, marginBottom: 20 }}>
              Notify accepts messages durably in PostgreSQL with immediate HTTP 202 acceptance, dispatches via transactional outbox, and provides complete lifecycle audit tracking.
            </p>

            <div style={{ display: 'flex', gap: 12, marginBottom: 24, flexWrap: 'wrap' }}>
              <Link
                to="/app/api-keys"
                className="btn btn-primary"
                style={{ textDecoration: 'none' }}
              >
                <Key size={14} />
                Create API Key
              </Link>
              <Link
                to="/app/notifications/new"
                className="btn btn-secondary"
                style={{ textDecoration: 'none' }}
              >
                <Plus size={14} />
                Send via UI
              </Link>
            </div>

            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-3)' }}>
                  Quick-start curl example:
                </span>
                <button
                  type="button"
                  onClick={handleCopyCurl}
                  className="btn btn-ghost btn-sm"
                  style={{
                    color: copiedCurl ? 'var(--color-st-delivered)' : 'var(--color-signal)',
                  }}
                >
                  {copiedCurl ? <Check size={13} /> : <Copy size={13} />}
                  <span>{copiedCurl ? 'Copied' : 'Copy curl'}</span>
                </button>
              </div>
              <pre
                className="code-excerpt"
                style={{ padding: 14 }}
              >
                {curlExample}
              </pre>
            </div>
          </div>
        </div>
      )}

      {/* Recent Deliveries Table */}
      {!isZeroState && (
        <div className="panel" style={{ padding: 0, overflow: 'hidden', marginBottom: 24 }}>
          <div
            style={{
              padding: '12px 20px',
              borderBottom: '1px solid var(--color-rail)',
              backgroundColor: 'var(--color-subtle)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
                Recent Deliveries
              </span>
              <span style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>• Last 10 notifications</span>
            </div>
            <Link
              to="/app/notifications"
              className="btn btn-ghost btn-sm"
              style={{
                color: 'var(--color-signal)',
                textDecoration: 'none',
                gap: 4,
              }}
            >
              All notifications <ExternalLink size={12} />
            </Link>
          </div>

          {recentNotifications.length === 0 ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-ink-3)', fontSize: 13 }}>
              No recent notifications to display.
            </div>
          ) : (
            <div className="table-container" style={{ border: 'none', borderRadius: 0 }}>
              <table className="dense-table">
                <thead>
                  <tr>
                    <th>Recipient</th>
                    <th>Channel</th>
                    <th>Lifecycle Progress</th>
                    <th>Status</th>
                    <th>Accepted</th>
                    <th style={{ textAlign: 'right' }}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {recentNotifications.map((notif) => (
                    <tr key={notif.id}>
                      <td>
                        <span className="mono" style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                          {notif.recipient}
                        </span>
                        {notif.subject && (
                          <div style={{ fontSize: 11, color: 'var(--color-ink-3)', textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap', maxWidth: 260 }}>
                            {notif.subject}
                          </div>
                        )}
                      </td>
                      <td>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600 }}>
                          {getChannelIcon(notif.channel, 13)}
                          <span>{notif.channel}</span>
                        </span>
                      </td>
                      <td>
                        <LifecycleRail
                          status={notif.status}
                          variant="compact"
                          attemptCount={1}
                        />
                      </td>
                      <td>
                        <span className={`status-chip status-${notif.status.toLowerCase()}`}>
                          {notif.status}
                        </span>
                      </td>
                      <td style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>
                        {new Date(notif.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Link
                          to={`/app/notifications/${notif.id}`}
                          className="btn btn-ghost btn-sm"
                          style={{
                            color: 'var(--color-signal)',
                            textDecoration: 'none',
                          }}
                        >
                          Inspect →
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Platform Invariants Panel */}
      <div className="panel" style={{ padding: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <ShieldCheck size={18} style={{ color: 'var(--color-signal)' }} />
          <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
            Architecture & Delivery Invariants
          </h2>
        </div>
        <ul style={{ paddingLeft: 20, color: 'var(--color-ink-2)', fontSize: 13, lineHeight: 1.8 }}>
          <li><strong>Authoritative Database:</strong> PostgreSQL is the single source of truth; Kafka messages carry identifiers only.</li>
          <li><strong>Deterministic Clock:</strong> Time in business domains is always accessed via injected <code className="mono">Clock</code> (UTC).</li>
          <li><strong>Safe Error Structure:</strong> RFC 9457 <code className="mono">application/problem+json</code> with frozen ErrorCode specifications.</li>
          <li><strong>Zero Inline Side-effects:</strong> API controllers return 202 on durable accept and never invoke outbound provider HTTP calls inline.</li>
          <li><strong>Tenant Isolation:</strong> Identity from verified credentials only. Cross-tenant access returns 404.</li>
        </ul>
      </div>
    </div>
  );
}

/**
 * Authenticated app shell wrapping the dashboard with sidebar navigation.
 */
export default function DashboardShell() {
  const { user, logout } = useAuth();
  const [health, setHealth] = useState<HealthState>({
    liveness: 'UNKNOWN',
    readiness: 'UNKNOWN',
    loading: true
  });

  useEffect(() => {
    async function checkHealth() {
      try {
        const liveRes = await fetch('/actuator/health/liveness');
        const liveData = liveRes.ok ? await liveRes.json() : { status: 'DOWN' };

        const readyRes = await fetch('/actuator/health/readiness');
        const readyData = readyRes.ok ? await readyRes.json() : { status: 'DOWN' };

        setHealth({
          liveness: liveData.status || 'DOWN',
          readiness: readyData.status || 'DOWN',
          loading: false
        });
      } catch {
        setHealth({
          liveness: 'OFFLINE',
          readiness: 'OFFLINE',
          loading: false
        });
      }
    }

    checkHealth();
    const timer = setInterval(checkHealth, 15000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div className="app-shell">
      {/* Sidebar Navigation */}
      <aside className="sidebar">
        <div className="sidebar-header">
          <div
            style={{
              width: 30,
              height: 30,
              borderRadius: 'var(--radius-control)',
              backgroundColor: 'var(--color-signal)',
              display: 'grid',
              placeItems: 'center',
              fontWeight: 800,
              color: '#FFFFFF',
              fontSize: 15,
              letterSpacing: '-0.02em',
              boxShadow: '0 2px 4px rgba(37, 99, 235, 0.3)',
            }}
          >
            N
          </div>
          <span className="sidebar-logo">Notify</span>
          <span
            style={{
              fontSize: 10,
              fontWeight: 700,
              color: 'var(--color-sidebar-text)',
              border: '1px solid var(--color-sidebar-border)',
              padding: '1px 5px',
              borderRadius: 4,
              marginLeft: 'auto',
            }}
          >
            v0.1
          </span>
        </div>

        <nav className="sidebar-nav">
          <NavLink
            to="/app"
            end
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <LayoutDashboard size={16} />
            <span>Overview</span>
          </NavLink>
          <NavLink
            to="/app/notifications"
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <Bell size={16} />
            <span>Notifications</span>
          </NavLink>
          <NavLink
            to="/app/templates"
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <FileText size={16} />
            <span>Templates</span>
          </NavLink>
          {Boolean(
            (user as any)?.role === 'TENANT_OWNER' ||
            (user as any)?.role === 'ROLE_TENANT_OWNER' ||
            user?.roles?.some((r) => r === 'TENANT_OWNER' || r === 'ROLE_TENANT_OWNER')
          ) && (
            <>
              <NavLink
                to="/app/api-keys"
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              >
                <Key size={16} />
                <span>API Keys</span>
              </NavLink>
              <NavLink
                to="/app/webhooks"
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              >
                <Webhook size={16} />
                <span>Webhooks</span>
              </NavLink>
            </>
          )}
          <NavLink
            to="/app/inbox"
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <Inbox size={16} />
            <span>In-App Inbox</span>
          </NavLink>
          <NavLink
            to="/app/settings"
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <Settings size={16} />
            <span>Settings</span>
          </NavLink>
        </nav>

        <div style={{ padding: '16px 16px', borderTop: '1px solid var(--color-sidebar-border)' }}>
          {user && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: '#F8FAFC', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {user.tenantName}
              </div>
              <div style={{ fontSize: 11, color: 'var(--color-sidebar-text)', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {user.email}
              </div>
            </div>
          )}
          <button
            type="button"
            onClick={logout}
            className="logout-button"
          >
            Sign Out
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="main-content">
        <header className="top-bar">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontWeight: 700, fontSize: 14, color: 'var(--color-harbor)' }}>
              {user?.tenantName || 'Workspace'}
            </span>
            <span className={`status-chip ${user?.tenantStatus === 'ACTIVE' ? 'status-delivered' : 'status-failed'}`}>
              <span className={user?.tenantStatus === 'ACTIVE' ? 'live-dot' : 'live-dot-paused'} style={{ width: 6, height: 6 }} />
              {user?.tenantStatus || 'Unknown'}
            </span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
              <span style={{ color: 'var(--color-ink-3)', fontWeight: 500 }}>Backend:</span>
              <span className={`status-chip ${health.liveness === 'UP' ? 'status-delivered' : 'status-failed'}`} style={{ padding: '2px 6px', fontSize: 10 }}>
                Live: {health.liveness}
              </span>
              <span className={`status-chip ${health.readiness === 'UP' ? 'status-delivered' : 'status-failed'}`} style={{ padding: '2px 6px', fontSize: 10 }}>
                Ready: {health.readiness}
              </span>
            </div>
            <div className="badge-mono" style={{ fontSize: 11, padding: '3px 8px' }}>
              UTC Clock: Injected
            </div>
            <ThemeToggle />
          </div>
        </header>

        <main style={{ flex: 1, padding: 0 }}>
          <TenantStatusBanner status={user?.tenantStatus} />
          <Routes>
            <Route index element={<OverviewContent />} />
            <Route path="notifications" element={<NotificationsPage />} />
            <Route path="notifications/new" element={<CreateNotificationPage />} />
            <Route path="notifications/:id" element={<NotificationDetailPage />} />
            <Route path="templates" element={<TemplatesPage />} />
            <Route path="templates/:id" element={<TemplateDetailPage />} />
            <Route path="api-keys" element={<ApiKeysPage />} />
            <Route path="webhooks" element={<WebhooksPage />} />
            <Route path="inbox" element={<InboxPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}
