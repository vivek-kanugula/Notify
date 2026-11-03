import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Activity,
  Database,
  Server,
  Zap,
  Calendar,
  RefreshCw,
  ExternalLink,
  Play,
  Pause,
} from 'lucide-react';
import {
  getAdminSystemStatus,
  type AdminSystemStatusResponse,
} from '../../api/adminSystem';

export default function AdminSystemPage() {
  const [data, setData] = useState<AdminSystemStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const timerRef = useRef<any>(null);

  const fetchStatus = useCallback(async () => {
    setError(null);
    try {
      const status = await getAdminSystemStatus();
      setData(status);
      setLastUpdated(new Date());
    } catch (err: any) {
      setError(err?.message || 'Failed to fetch system status telemetry.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  // 10s auto-refresh loop with tab visibility pausing
  useEffect(() => {
    if (!autoRefresh) {
      if (timerRef.current) clearInterval(timerRef.current);
      return;
    }

    const startPolling = () => {
      if (timerRef.current) clearInterval(timerRef.current);
      timerRef.current = setInterval(() => {
        if (!document.hidden) {
          fetchStatus();
        }
      }, 10000);
    };

    const handleVisibilityChange = () => {
      if (!document.hidden && autoRefresh) {
        fetchStatus();
        startPolling();
      } else if (document.hidden) {
        if (timerRef.current) clearInterval(timerRef.current);
      }
    };

    startPolling();
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [autoRefresh, fetchStatus]);

  const getStatusBadge = (status: string) => {
    const isUp = status === 'UP';
    const isDegraded = status === 'DEGRADED';
    return (
      <span
        className={`chip ${isUp ? 'chip-delivered' : isDegraded ? 'chip-retrying' : 'chip-failed'}`}
        style={{ fontSize: 11, padding: '2px 8px' }}
      >
        <span
          style={{
            width: 6,
            height: 6,
            borderRadius: '50%',
            backgroundColor: isUp
              ? 'var(--color-st-delivered)'
              : isDegraded
              ? 'var(--color-st-retrying)'
              : 'var(--color-st-failed)',
          }}
        />
        <span>{status}</span>
      </span>
    );
  };

  const getBreakerBadge = (state: string) => {
    switch (state) {
      case 'CLOSED':
        return (
          <span className="chip chip-delivered" style={{ fontSize: 11, padding: '2px 8px' }}>
            CLOSED (Healthy)
          </span>
        );
      case 'HALF_OPEN':
        return (
          <span className="chip chip-retrying" style={{ fontSize: 11, padding: '2px 8px' }}>
            HALF-OPEN (Probing)
          </span>
        );
      case 'OPEN':
        return (
          <span className="chip chip-failed" style={{ fontSize: 11, padding: '2px 8px' }}>
            OPEN (Tripped)
          </span>
        );
      default:
        return <span className="chip" style={{ fontSize: 11 }}>{state}</span>;
    }
  };

  const getBreakerGuidance = (name: string, state: string) => {
    if (state === 'CLOSED') {
      return 'Operating normally. Outbound attempts execute in real time against providers.';
    }
    if (state === 'HALF_OPEN') {
      return 'Circuit breaker is probing downstream recovery with trial requests.';
    }
    if (state === 'OPEN') {
      return `${name} tripped due to excessive failures. Dispatch is queued safely without exhausting attempt budgets.`;
    }
    return 'Resilience state actively monitored.';
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
            <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
              System Status & Infrastructure
            </h1>
            {data && getStatusBadge(data.status)}
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>
            Component health, circuit breaker telemetry, background worker states, and observability links.
          </p>
        </div>

        {/* Controls */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button
            type="button"
            onClick={() => setAutoRefresh(!autoRefresh)}
            className="btn btn-secondary"
            style={{ fontSize: 11, padding: '6px 12px' }}
          >
            {autoRefresh ? <Pause size={11} /> : <Play size={11} />}
            <span>Auto-refresh: <strong style={{ color: autoRefresh ? 'var(--color-st-delivered)' : 'var(--color-ink-2)' }}>{autoRefresh ? '10s' : 'Paused'}</strong></span>
          </button>

          <button
            type="button"
            onClick={fetchStatus}
            disabled={loading}
            className="btn btn-secondary"
            style={{ fontSize: 11, padding: '6px 12px' }}
          >
            <RefreshCw size={11} className={loading ? 'spin-slow' : ''} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {lastUpdated && (
        <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginBottom: 20 }}>
          Last poll: <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{lastUpdated.toLocaleTimeString()}</span>
        </div>
      )}

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

      {/* Core Infrastructure Health Grid */}
      <div style={{ marginBottom: 28 }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, margin: '0 0 12px', color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
          Core Infrastructure Components
        </h2>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
            gap: 14,
          }}
        >
          {/* PostgreSQL */}
          <div className="panel" style={{ padding: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Database size={16} style={{ color: 'var(--color-signal)' }} />
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>PostgreSQL</span>
              </div>
              {data?.components?.db && getStatusBadge(data.components.db.status)}
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-ink-2)', display: 'flex', flexDirection: 'column', gap: 4 }}>
              <div>Authoritative Database (RDBMS)</div>
              {data?.components?.db?.details && (
                <div className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>
                  Active: {String(data.components.db.details.poolActive ?? '—')} • Idle: {String(data.components.db.details.poolIdle ?? '—')}
                </div>
              )}
            </div>
          </div>

          {/* Redis */}
          <div className="panel" style={{ padding: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Zap size={16} style={{ color: 'var(--color-st-retrying)' }} />
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>Redis</span>
              </div>
              {data?.components?.redis && getStatusBadge(data.components.redis.status)}
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-ink-2)', display: 'flex', flexDirection: 'column', gap: 4 }}>
              <div>Rate Limiting & Auth Cache (Fail-open)</div>
              {data?.components?.redis?.details && (
                <div className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>
                  Latency: {String(data.components.redis.details.pingLatencyMs ?? '—')} ms
                </div>
              )}
            </div>
          </div>

          {/* Kafka */}
          <div className="panel" style={{ padding: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Server size={16} style={{ color: 'var(--color-harbor)' }} />
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>Kafka</span>
              </div>
              {data?.components?.kafka && getStatusBadge(data.components.kafka.status)}
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-ink-2)', display: 'flex', flexDirection: 'column', gap: 4 }}>
              <div>Message Bus (Outbox Delivery Engine)</div>
              {data?.components?.kafka?.details && (
                <div className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)' }}>
                  Brokers: {String(data.components.kafka.details.brokers ?? '—')}
                </div>
              )}
            </div>
          </div>

          {/* Quartz */}
          <div className="panel" style={{ padding: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Calendar size={16} style={{ color: 'var(--color-signal)' }} />
                <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>Quartz</span>
              </div>
              {data?.components?.quartz && getStatusBadge(data.components.quartz.status)}
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-ink-2)', display: 'flex', flexDirection: 'column', gap: 4 }}>
              <div>Scheduled Releaser & Reaper</div>
              {data?.components?.quartz?.details && (
                <div className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)', fontVariantNumeric: 'tabular-nums' }}>
                  Pool size: {String(data.components.quartz.details.threadPoolSize ?? '—')}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Circuit Breakers Grid */}
      <div style={{ marginBottom: 28 }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, margin: '0 0 12px', color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
          Resilience Circuit Breakers & Outbound Adapters
        </h2>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))',
            gap: 16,
          }}
        >
          {!data?.circuitBreakers || data.circuitBreakers.length === 0 ? (
            <div className="panel" style={{ padding: 24, textAlign: 'center', color: 'var(--color-ink-2)' }}>
              No circuit breakers currently registered.
            </div>
          ) : (
            data.circuitBreakers.map((cb) => {
              const failureRateNum = typeof cb.failureRate === 'number' ? cb.failureRate : Number(cb.failureRate) || 0;
              const safeFailureRate = Number.isFinite(failureRateNum) && failureRateNum >= 0 ? failureRateNum : 0;
              return (
                <div key={cb.name} className="panel" style={{ padding: 18 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <Activity size={15} style={{ color: 'var(--color-signal)' }} />
                      <span className="mono" style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
                        {cb.name}
                      </span>
                    </div>
                    {getBreakerBadge(cb.state)}
                  </div>

                  {/* Failure Rate Bar */}
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--color-ink-2)', marginBottom: 4 }}>
                      <span>Failure Rate</span>
                      <span className="mono" style={{ fontVariantNumeric: 'tabular-nums' }}>{safeFailureRate.toFixed(1)}%</span>
                    </div>
                    <div
                      style={{
                        height: 6,
                        backgroundColor: 'var(--color-chalk)',
                        borderRadius: 3,
                        overflow: 'hidden',
                        border: '1px solid var(--color-rail)',
                      }}
                    >
                      <div
                        style={{
                          width: `${Math.min(safeFailureRate, 100)}%`,
                          height: '100%',
                          backgroundColor:
                            safeFailureRate > 50
                              ? 'var(--color-st-failed)'
                              : safeFailureRate > 20
                              ? 'var(--color-st-retrying)'
                              : 'var(--color-st-delivered)',
                          transition: 'width 0.3s ease',
                        }}
                      />
                    </div>
                  </div>

                  {/* Guidance */}
                  <div
                    style={{
                      padding: '8px 12px',
                      backgroundColor: 'var(--color-chalk)',
                      border: '1px solid var(--color-rail)',
                      borderRadius: 'var(--radius-control)',
                      fontSize: 11,
                      color: 'var(--color-ink-1)',
                      lineHeight: 1.4,
                    }}
                  >
                    {getBreakerGuidance(cb.name, cb.state)}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* External Observability Links */}
      <div className="panel" style={{ padding: 20 }}>
        <h3 style={{ fontSize: 13, fontWeight: 700, margin: '0 0 8px', color: 'var(--color-harbor)' }}>
          External Observability & Telemetry Dashboards
        </h3>
        <p style={{ margin: '0 0 14px', fontSize: 12, color: 'var(--color-ink-2)' }}>
          Direct deep-links to platform Grafana metrics dashboards, Loki log aggregators, and Tempo distributed traces.
        </p>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <a
            href={data?.grafanaLinks?.overview || '/grafana/d/notify-overview/notify-overview'}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-secondary"
            style={{
              fontSize: 12,
              textDecoration: 'none',
              padding: '6px 12px',
            }}
          >
            <span>Grafana Overview</span>
            <ExternalLink size={11} />
          </a>

          <a
            href={data?.grafanaLinks?.logs || '/grafana/explore?schemaVersion=1&panes=%7B%22A%22%3A%7B%22datasource%22%3A%22notify-loki%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22%7Bservice_name%3D~%5C%22.%2B%5C%22%7D%22%2C%22queryType%22%3A%22range%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D'}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-secondary"
            style={{
              fontSize: 12,
              textDecoration: 'none',
              padding: '6px 12px',
            }}
          >
            <span>Loki Structured Logs</span>
            <ExternalLink size={11} />
          </a>

          <a
            href={data?.grafanaLinks?.traces || '/grafana/explore?schemaVersion=1&panes=%7B%22A%22%3A%7B%22datasource%22%3A%22notify-tempo%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22queryType%22%3A%22traceql%22%2C%22query%22%3A%22%7B%7D%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D'}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-secondary"
            style={{
              fontSize: 12,
              textDecoration: 'none',
              padding: '6px 12px',
            }}
          >
            <span>Tempo Distributed Traces</span>
            <ExternalLink size={11} />
          </a>
        </div>
      </div>
    </div>
  );
}