import React, { useState } from 'react';
import type { UsageTimeseries, UsageBucket, UsageSummary } from '../api/usage';
import { Mail, Webhook, Bell } from 'lucide-react';

export interface ChartPanelProps {
  timeseries: UsageTimeseries | null;
  summary: UsageSummary | null;
  loading?: boolean;
  className?: string;
}

export const ChartPanel: React.FC<ChartPanelProps> = ({
  timeseries,
  summary,
  loading = false,
  className = '',
}) => {
  const [hoveredBucket, setHoveredBucket] = useState<UsageBucket | null>(null);

  const buckets = timeseries?.buckets || [];
  const maxVolume = Math.max(
    ...buckets.map((b) => b.delivered + b.failed + b.cancelled),
    10
  );

  const chartHeight = 180;
  const chartWidth = 600;
  const barWidth = buckets.length > 0 ? Math.min(32, Math.floor((chartWidth - 40) / buckets.length) - 6) : 24;

  return (
    <div
      className={`chart-panel panel ${className}`}
      style={{
        padding: '20px 24px',
        marginBottom: 20,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 2 }}>
            Deliveries Over Time
          </h2>
          <span style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>
            Daily volume stacked by delivery outcome (Delivered · Failed · Cancelled)
          </span>
        </div>

        {/* Legend */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, fontSize: 11, fontWeight: 600 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, backgroundColor: 'var(--color-st-delivered)' }} />
            <span>Delivered</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, backgroundColor: 'var(--color-st-failed)' }} />
            <span>Failed</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, backgroundColor: 'var(--color-st-cancelled)' }} />
            <span>Cancelled</span>
          </div>
        </div>
      </div>

      {loading ? (
        <div style={{ height: chartHeight, display: 'grid', placeItems: 'center', color: 'var(--color-ink-3)' }}>
          <div className="loading-spinner" style={{ marginBottom: 8 }} />
          Loading analytics…
        </div>
      ) : buckets.length === 0 ? (
        <div
          style={{
            height: chartHeight,
            display: 'grid',
            placeItems: 'center',
            color: 'var(--color-ink-3)',
            fontSize: 13,
            backgroundColor: 'var(--color-subtle)',
            borderRadius: 'var(--radius-control)',
          }}
        >
          No volume data recorded in the selected time window.
        </div>
      ) : (
        <div style={{ position: 'relative' }}>
          {/* SVG Stacked Bar Chart */}
          <svg
            viewBox={`0 0 ${chartWidth} ${chartHeight + 30}`}
            style={{ width: '100%', height: 'auto', overflow: 'visible' }}
          >
            {/* Gridlines */}
            {[0, 0.25, 0.5, 0.75, 1].map((pct) => {
              const y = chartHeight - pct * chartHeight;
              const val = Math.round(pct * maxVolume);
              return (
                <g key={pct}>
                  <line
                    x1="30"
                    y1={y}
                    x2={chartWidth}
                    y2={y}
                    stroke="var(--color-rail)"
                    strokeWidth="1"
                    strokeDasharray="3 3"
                    opacity="0.6"
                  />
                  <text
                    x="24"
                    y={y + 4}
                    fontSize="9"
                    fill="var(--color-ink-3)"
                    textAnchor="end"
                    fontFamily="var(--font-mono)"
                  >
                    {val}
                  </text>
                </g>
              );
            })}

            {/* Bars */}
            {buckets.map((b, idx) => {
              const total = b.delivered + b.failed + b.cancelled;
              const x = 40 + idx * ((chartWidth - 50) / buckets.length) + (chartWidth - 50) / (buckets.length * 2) - barWidth / 2;

              const delHeight = (b.delivered / maxVolume) * chartHeight;
              const failHeight = (b.failed / maxVolume) * chartHeight;
              const cancHeight = (b.cancelled / maxVolume) * chartHeight;

              const delY = chartHeight - delHeight;
              const failY = delY - failHeight;
              const cancY = failY - cancHeight;

              const dateLabel = new Date(b.bucket).toLocaleDateString(undefined, { month: 'numeric', day: 'numeric', timeZone: 'UTC' });

              return (
                <g
                  key={b.bucket}
                  onMouseEnter={() => setHoveredBucket(b)}
                  onMouseLeave={() => setHoveredBucket(null)}
                  style={{ cursor: 'pointer' }}
                >
                  {/* Hit area for hover */}
                  <rect
                    x={x - 4}
                    y={0}
                    width={barWidth + 8}
                    height={chartHeight}
                    fill="transparent"
                  />

                  {/* Delivered segment */}
                  {b.delivered > 0 && (
                    <rect
                      x={x}
                      y={delY}
                      width={barWidth}
                      height={Math.max(delHeight, 2)}
                      fill="var(--color-st-delivered)"
                      rx="2"
                    />
                  )}

                  {/* Failed segment */}
                  {b.failed > 0 && (
                    <rect
                      x={x}
                      y={failY}
                      width={barWidth}
                      height={Math.max(failHeight, 2)}
                      fill="var(--color-st-failed)"
                      rx="2"
                    />
                  )}

                  {/* Cancelled segment */}
                  {b.cancelled > 0 && (
                    <rect
                      x={x}
                      y={cancY}
                      width={barWidth}
                      height={Math.max(cancHeight, 2)}
                      fill="var(--color-st-cancelled)"
                      rx="2"
                    />
                  )}

                  {/* Empty base tick if 0 total */}
                  {total === 0 && (
                    <rect
                      x={x}
                      y={chartHeight - 2}
                      width={barWidth}
                      height={2}
                      fill="var(--color-rail)"
                      rx="1"
                    />
                  )}

                  {/* X Axis Label */}
                  <text
                    x={x + barWidth / 2}
                    y={chartHeight + 16}
                    fontSize="10"
                    fill="var(--color-ink-3)"
                    textAnchor="middle"
                    fontFamily="var(--font-sans)"
                  >
                    {dateLabel}
                  </text>
                </g>
              );
            })}
          </svg>

          {/* Hover Tooltip */}
          {hoveredBucket && (
            <div
              style={{
                position: 'absolute',
                top: 10,
                right: 16,
                backgroundColor: 'var(--color-tooltip-bg)',
                color: 'var(--color-tooltip-text)',
                border: '1px solid var(--color-rail)',
                padding: '8px 12px',
                borderRadius: 'var(--radius-control)',
                fontSize: 12,
                boxShadow: 'var(--shadow-overlay)',
                pointerEvents: 'none',
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: 4 }}>
                {new Date(hoveredBucket.bucket).toLocaleDateString(undefined, { timeZone: 'UTC', year: 'numeric', month: 'short', day: 'numeric' })}
              </div>
              <div style={{ display: 'flex', gap: 10, fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                <span>Delivered: <strong>{hoveredBucket.delivered}</strong></span>
                <span>Failed: <strong>{hoveredBucket.failed}</strong></span>
                <span>Cancelled: <strong>{hoveredBucket.cancelled}</strong></span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Channel Distribution Breakdown */}
      {summary?.byChannel && (
        <div style={{ marginTop: 24, borderTop: '1px solid var(--color-rail)', paddingTop: 16 }}>
          <h3 style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 12 }}>
            Volume Distribution by Channel
          </h3>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
              gap: 12,
            }}
          >
            {(['EMAIL', 'WEBHOOK', 'IN_APP'] as const).map((ch) => {
              const counts = summary.byChannel[ch] || { accepted: 0, delivered: 0, failed: 0 };
              return (
                <div
                  key={ch}
                  style={{
                    backgroundColor: 'var(--color-subtle)',
                    padding: '12px 14px',
                    borderRadius: 'var(--radius-control)',
                    border: '1px solid var(--color-rail)',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 600, fontSize: 13, marginBottom: 8, color: 'var(--color-harbor)' }}>
                    {ch === 'EMAIL' && <Mail size={14} />}
                    {ch === 'WEBHOOK' && <Webhook size={14} />}
                    {ch === 'IN_APP' && <Bell size={14} />}
                    <span>{ch === 'IN_APP' ? 'In-App' : ch.charAt(0) + ch.slice(1).toLowerCase()}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 3 }}>
                    <span style={{ color: 'var(--color-ink-3)' }}>Accepted:</span>
                    <strong className="mono tabular-nums">{counts.accepted.toLocaleString()}</strong>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 3 }}>
                    <span style={{ color: 'var(--color-st-delivered)' }}>Delivered:</span>
                    <strong className="mono tabular-nums" style={{ color: 'var(--color-st-delivered)' }}>
                      {counts.delivered.toLocaleString()}
                    </strong>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                    <span style={{ color: 'var(--color-st-failed)' }}>Failed:</span>
                    <strong className="mono tabular-nums" style={{ color: 'var(--color-st-failed)' }}>
                      {counts.failed.toLocaleString()}
                    </strong>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};

export default ChartPanel;
