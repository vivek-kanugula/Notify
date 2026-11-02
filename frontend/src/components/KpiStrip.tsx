import React from 'react';
import type { UsageSummary } from '../api/usage';

export interface KpiStripProps {
  summary: UsageSummary | null;
  loading?: boolean;
  timeRange: '24h' | '7d' | '30d';
  onTimeRangeChange: (range: '24h' | '7d' | '30d') => void;
  className?: string;
}

export const KpiStrip: React.FC<KpiStripProps> = ({
  summary,
  loading = false,
  timeRange,
  onTimeRangeChange,
  className = '',
}) => {
  const successRatePercent =
    summary && typeof summary.deliverySuccessRate === 'number' && !Number.isNaN(summary.deliverySuccessRate)
      ? (summary.deliverySuccessRate * 100).toFixed(1)
      : '—';

  return (
    <div
      className={`kpi-strip-container panel ${className}`}
      style={{
        padding: 0,
        marginBottom: 20,
        overflow: 'hidden',
      }}
    >
      {/* Time Range Filter Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '12px 20px',
          borderBottom: '1px solid var(--color-rail)',
          backgroundColor: 'var(--color-subtle)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
            Delivery Performance
          </span>
          <span style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>• Business Telemetry</span>
        </div>

        {/* Range Segmented Control */}
        <div
          style={{
            display: 'inline-flex',
            backgroundColor: 'var(--color-paper)',
            borderRadius: 'var(--radius-control)',
            padding: 2,
            border: '1px solid var(--color-rail)',
          }}
        >
          {(['24h', '7d', '30d'] as const).map((r) => (
            <button
              key={r}
              type="button"
              onClick={() => onTimeRangeChange(r)}
              style={{
                padding: '4px 10px',
                fontSize: 12,
                fontWeight: timeRange === r ? 600 : 500,
                backgroundColor: timeRange === r ? 'var(--color-signal)' : 'transparent',
                color: timeRange === r ? '#FFFFFF' : 'var(--color-ink-2)',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                transition: 'all 0.15s ease-out',
              }}
            >
              {r === '24h' ? '24 Hours' : r === '7d' ? '7 Days' : '30 Days'}
            </button>
          ))}
        </div>
      </div>

      {/* Ruled Continuous KPI Strip with hairline dividers */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          backgroundColor: 'var(--color-paper)',
        }}
        className="kpi-strip-cells"
      >
        {/* Cell 1: Total Accepted */}
        <div style={{ padding: '16px 20px', borderRight: '1px solid var(--color-rail)' }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)', display: 'block', marginBottom: 4 }}>
            Total Accepted
          </span>
          <div className="mono tabular-nums" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
            {loading ? '…' : summary?.totalAccepted != null ? summary.totalAccepted.toLocaleString() : '0'}
          </div>
          <span style={{ fontSize: 11, color: 'var(--color-ink-3)', marginTop: 2, display: 'block' }}>
            202 Ingress acceptance
          </span>
        </div>

        {/* Cell 2: Delivered */}
        <div style={{ padding: '16px 20px', borderRight: '1px solid var(--color-rail)' }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-st-delivered)', display: 'block', marginBottom: 4 }}>
            Delivered
          </span>
          <div className="mono tabular-nums" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-st-delivered)', letterSpacing: '-0.02em' }}>
            {loading ? '…' : summary?.totalDelivered != null ? summary.totalDelivered.toLocaleString() : '0'}
          </div>
          <span style={{ fontSize: 11, color: 'var(--color-ink-3)', marginTop: 2, display: 'block' }}>
            Terminal provider 200
          </span>
        </div>

        {/* Cell 3: In Progress / Processing */}
        <div style={{ padding: '16px 20px', borderRight: '1px solid var(--color-rail)' }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-st-processing)', display: 'block', marginBottom: 4 }}>
            In Progress
          </span>
          <div className="mono tabular-nums" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-st-processing)', letterSpacing: '-0.02em' }}>
            {loading ? '…' : summary?.totalProcessing != null ? summary.totalProcessing.toLocaleString() : '0'}
          </div>
          <span style={{ fontSize: 11, color: 'var(--color-ink-3)', marginTop: 2, display: 'block' }}>
            Active worker claims
          </span>
        </div>

        {/* Cell 4: Failed */}
        <div style={{ padding: '16px 20px', borderRight: '1px solid var(--color-rail)' }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-st-failed)', display: 'block', marginBottom: 4 }}>
            Failed / DLQ
          </span>
          <div className="mono tabular-nums" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-st-failed)', letterSpacing: '-0.02em' }}>
            {loading ? '…' : summary?.totalFailed != null ? summary.totalFailed.toLocaleString() : '0'}
          </div>
          <span style={{ fontSize: 11, color: 'var(--color-ink-3)', marginTop: 2, display: 'block' }}>
            Terminal exhausted retry
          </span>
        </div>

        {/* Cell 5: Success Rate (%) */}
        <div style={{ padding: '16px 20px' }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', display: 'block', marginBottom: 4 }}>
            Delivery Success Rate
          </span>
          <div className="mono tabular-nums" style={{ fontSize: 24, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
            {loading ? '…' : `${successRatePercent}%`}
          </div>
          <span style={{ fontSize: 11, color: 'var(--color-ink-3)', marginTop: 2, display: 'block' }}>
            Delivered / (Delivered + Failed)
          </span>
        </div>
      </div>
    </div>
  );
};

export default KpiStrip;
