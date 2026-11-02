import { useState, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import {
  BarChart2,
  CheckCircle,
  AlertOctagon,
  Clock,
  Layers,
  Archive,
  RefreshCw,
  ExternalLink,
  Activity,
} from 'lucide-react';
import {
  getAdminOverviewAnalytics,
  type AdminAnalyticsOverviewResponse,
} from '../../api/adminAnalytics';
import LifecycleRail from '../../components/LifecycleRail';
import type { RailStageName } from '../../components/lifecycleRailLogic';

export default function AdminOverviewPage() {
  const navigate = useNavigate();
  const [timeRange, setTimeRange] = useState<'24h' | '7d' | '30d'>('7d');
  const [data, setData] = useState<AdminAnalyticsOverviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchOverview = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const now = new Date();
      const days = timeRange === '24h' ? 1 : timeRange === '7d' ? 7 : 30;
      const fromDate = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);

      const overview = await getAdminOverviewAnalytics({
        from: fromDate.toISOString(),
        to: now.toISOString(),
      });
      setData(overview);
    } catch (err: any) {
      setError(err?.message || 'Failed to load platform analytics overview.');
    } finally {
      setLoading(false);
    }
  }, [timeRange]);

  useEffect(() => {
    fetchOverview();
  }, [fetchOverview]);

  const handleStageClick = (stage: RailStageName) => {
    if (stage === 'DLQ') {
      navigate('/admin/dlq?status=OPEN');
    } else {
      navigate(`/admin/notifications?status=${stage}`);
    }
  };

  const aggregateCounts: Record<RailStageName, number> = {
    SCHEDULED: 0,
    QUEUED: data?.queueDepth ?? 0,
    PROCESSING: data?.activeCount ?? 0,
    RETRYING: 0,
    DELIVERED: data?.deliveredCount ?? 0,
    FAILED: data?.failedCount ?? 0,
    DLQ: data?.dlqOpenCount ?? 0,
    CANCELLED: data?.cancelledCount ?? 0,
  };

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Header Bar */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 16,
          marginBottom: 24,
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
              System Overview & Telemetry
            </h1>
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>
            Cross-tenant delivery throughput, aggregate pipeline health, latency percentiles, and traffic distributions.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {/* Segmented Time Range Selector */}
          <div
            style={{
              display: 'inline-flex',
              backgroundColor: 'var(--color-chalk)',
              borderRadius: 'var(--radius-control)',
              padding: 2,
              border: '1px solid var(--color-rail)',
            }}
          >
            {(['24h', '7d', '30d'] as const).map((r) => (
              <button
                key={r}
                type="button"
                onClick={() => setTimeRange(r)}
                style={{
                  padding: '5px 12px',
                  fontSize: 11,
                  fontWeight: timeRange === r ? 700 : 500,
                  backgroundColor: timeRange === r ? 'var(--color-paper)' : 'transparent',
                  color: timeRange === r ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                  border: 'none',
                  borderRadius: 4,
                  cursor: 'pointer',
                  boxShadow: timeRange === r ? 'var(--shadow-card)' : 'none',
                  transition: 'all 0.15s ease',
                  textTransform: 'uppercase',
                }}
              >
                {r}
              </button>
            ))}
          </div>

          <button
            type="button"
            onClick={fetchOverview}
            disabled={loading}
            className="btn btn-secondary"
            title="Refresh analytics data"
            style={{ fontSize: 12, padding: '6px 12px' }}
          >
            <RefreshCw size={12} className={loading ? 'spin-slow' : ''} />
            <span>Refresh</span>
          </button>
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

      {/* KPI Cards Strip */}
      <div
        className="kpi-grid"
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
          gap: 14,
          marginBottom: 24,
        }}
      >
        {/* Total Notifications */}
        <div className="panel" style={{ padding: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)' }}>
              Total Volume
            </span>
            <BarChart2 size={15} style={{ color: 'var(--color-signal)' }} />
          </div>
          <div className="mono" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>
            {loading ? '—' : (data?.totalNotifications ?? 0).toLocaleString()}
          </div>
          <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4 }}>
            <span>Delivered: </span>
            <strong className="mono" style={{ color: 'var(--color-st-delivered)', fontVariantNumeric: 'tabular-nums' }}>
              {loading ? '—' : (data?.deliveredCount ?? 0).toLocaleString()}
            </strong>
          </div>
        </div>

        {/* Global Success Rate */}
        <div className="panel" style={{ padding: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)' }}>
              Global Success Rate
            </span>
            <CheckCircle size={15} style={{ color: 'var(--color-st-delivered)' }} />
          </div>
          {(() => {
            const rawRate = data?.globalSuccessRate ?? 0;
            const successRatePercent = rawRate <= 1.0 ? rawRate * 100 : rawRate;
            return (
              <div
                className="mono"
                style={{
                  fontSize: 24,
                  fontWeight: 700,
                  fontVariantNumeric: 'tabular-nums',
                  color: successRatePercent >= 90 ? 'var(--color-st-delivered)' : 'var(--color-st-retrying)',
                }}
              >
                {loading ? '—' : `${successRatePercent.toFixed(1)}%`}
              </div>
            );
          })()}
          <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4 }}>
            <span>Failed: </span>
            <strong className="mono" style={{ color: 'var(--color-st-failed)', fontVariantNumeric: 'tabular-nums' }}>
              {loading ? '—' : (data?.failedCount ?? 0).toLocaleString()}
            </strong>
          </div>
        </div>

        {/* Delivery Latency (P95) */}
        <div className="panel" style={{ padding: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)' }}>
              Latency (p95)
            </span>
            <Clock size={15} style={{ color: 'var(--color-harbor)' }} />
          </div>
          <div className="mono" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>
            {loading ? '—' : `${data?.latencyPercentilesMs?.p95 ?? 0} ms`}
          </div>
          <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'flex', gap: 8 }}>
            <span>p50: <strong className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{data?.latencyPercentilesMs?.p50 ?? 0}ms</strong></span>
            <span>p99: <strong className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{data?.latencyPercentilesMs?.p99 ?? 0}ms</strong></span>
          </div>
        </div>

        {/* Queue Depth / Active In-Flight */}
        <div className="panel" style={{ padding: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)' }}>
              Queue Depth
            </span>
            <Layers size={15} style={{ color: 'var(--color-st-queued)' }} />
          </div>
          <div className="mono" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>
            {loading ? '—' : (data?.queueDepth ?? 0).toLocaleString()}
          </div>
          <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4 }}>
            <span>Active in-flight: </span>
            <strong className="mono" style={{ color: 'var(--color-st-processing)', fontVariantNumeric: 'tabular-nums' }}>
              {loading ? '—' : (data?.activeCount ?? 0).toLocaleString()}
            </strong>
          </div>
        </div>

        {/* Open DLQ Entries */}
        <div
          className="panel"
          style={{
            padding: 18,
            cursor: 'pointer',
            border: (data?.dlqOpenCount ?? 0) > 0 ? '1px solid rgba(138, 77, 212, 0.4)' : undefined,
            transition: 'border-color 0.15s ease',
          }}
          onClick={() => navigate('/admin/dlq?status=OPEN')}
          title="Inspect open Dead-Letter Queue entries"
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-st-dlq)' }}>
              Open DLQ
            </span>
            <Archive size={15} style={{ color: 'var(--color-st-dlq)' }} />
          </div>
          <div className="mono" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-st-dlq)', fontVariantNumeric: 'tabular-nums' }}>
            {loading ? '—' : (data?.dlqOpenCount ?? 0).toLocaleString()}
          </div>
          <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
            <span>Inspect queue</span>
            <ExternalLink size={11} />
          </div>
        </div>
      </div>

      {/* Aggregate Lifecycle Rail Section */}
      <div style={{ marginBottom: 28 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Activity size={15} style={{ color: 'var(--color-signal)' }} />
            <h2 style={{ fontSize: 14, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
              Aggregate Pipeline Lifecycle
            </h2>
          </div>
          <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
            Select any lifecycle node to inspect filtered notifications
          </span>
        </div>

        <LifecycleRail
          status="QUEUED"
          variant="aggregate"
          aggregateCounts={aggregateCounts}
          onClickStage={handleStageClick}
        />
      </div>

      {/* 2-Column Analytics Breakdown */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))',
          gap: 20,
        }}
      >
        {/* Top Tenants by Volume */}
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
              Top Tenants by Volume
            </h3>
            <Link
              to="/admin/tenants"
              style={{ fontSize: 11, color: 'var(--color-signal)', textDecoration: 'none', fontWeight: 600 }}
            >
              All tenants →
            </Link>
          </div>

          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr>
                  <th style={{ width: 40 }}>#</th>
                  <th>Tenant</th>
                  <th>Slug</th>
                  <th style={{ textAlign: 'right' }}>Notifications</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={4} style={{ textAlign: 'center', padding: 24, color: 'var(--color-ink-2)' }}>
                      Loading tenant metrics…
                    </td>
                  </tr>
                ) : !data?.topTenants || data.topTenants.length === 0 ? (
                  <tr>
                    <td colSpan={4} style={{ textAlign: 'center', padding: 24, color: 'var(--color-ink-2)' }}>
                      No tenant activity recorded in this time range.
                    </td>
                  </tr>
                ) : (
                  data.topTenants.map((tenant, idx) => (
                    <tr key={tenant.tenantId}>
                      <td className="mono" style={{ color: 'var(--color-ink-2)', fontVariantNumeric: 'tabular-nums' }}>
                        {idx + 1}
                      </td>
                      <td>
                        <Link
                          to={`/admin/tenants/${tenant.tenantId}`}
                          style={{ color: 'var(--color-harbor)', fontWeight: 600, textDecoration: 'none' }}
                        >
                          {tenant.name}
                        </Link>
                      </td>
                      <td>
                        <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
                          {tenant.slug}
                        </span>
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <strong className="mono" style={{ color: 'var(--color-signal)', fontVariantNumeric: 'tabular-nums' }}>
                          {tenant.volume.toLocaleString()}
                        </strong>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Top Failure Reasons */}
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
              Top Delivery Failure Reasons
            </h3>
            <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>By occurrence</span>
          </div>

          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr>
                  <th>Reason Code</th>
                  <th style={{ width: 140, textAlign: 'right' }}>Occurrences</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={2} style={{ textAlign: 'center', padding: 24, color: 'var(--color-ink-2)' }}>
                      Loading failure data…
                    </td>
                  </tr>
                ) : !data?.topFailureReasons || data.topFailureReasons.length === 0 ? (
                  <tr>
                    <td colSpan={2} style={{ textAlign: 'center', padding: 24, color: 'var(--color-ink-2)' }}>
                      No delivery failures recorded in this period.
                    </td>
                  </tr>
                ) : (
                  data.topFailureReasons.map((fail) => (
                    <tr key={fail.reason}>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <AlertOctagon size={13} style={{ color: 'var(--color-st-failed)' }} />
                          <span className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)', fontWeight: 600 }}>
                            {fail.reason}
                          </span>
                        </div>
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <strong className="mono" style={{ color: 'var(--color-st-failed)', fontVariantNumeric: 'tabular-nums' }}>
                          {fail.count.toLocaleString()}
                        </strong>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}