import React from 'react';
import type { NotificationStatus, NotificationHistoryEntry } from '../api/notifications';
import {
  computeLifecycleStages,
  computeCompactSegments,
  type RailStageName,
  type StageState,
} from './lifecycleRailLogic';
import {
  CalendarClock,
  Layers,
  Loader2,
  RotateCw,
  Check,
  X,
  Archive,
  Ban,
} from 'lucide-react';

export interface LifecycleRailProps {
  status: NotificationStatus;
  scheduledAt?: string | null;
  queuedAt?: string | null;
  processingStartedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string | null;
  attemptCount?: number;
  maxAttempts?: number;
  history?: NotificationHistoryEntry[];
  variant?: 'full' | 'compact' | 'aggregate';
  aggregateCounts?: Record<RailStageName, number>;
  onClickStage?: (stage: RailStageName) => void;
  className?: string;
  style?: React.CSSProperties;
}

export function getStageIcon(stageName: RailStageName, state: StageState, size = 14) {
  if (state === 'done') {
    return <Check size={size} aria-hidden="true" strokeWidth={2.5} />;
  }
  if (state === 'upcoming' && stageName === 'PROCESSING') {
    return <Loader2 size={size} aria-hidden="true" />;
  }
  return getStatusIcon(stageName, size);
}

export function getStatusIcon(status: NotificationStatus | RailStageName, size = 14) {
  switch (status) {
    case 'SCHEDULED':
      return <CalendarClock size={size} aria-hidden="true" />;
    case 'CREATED':
    case 'QUEUED':
      return <Layers size={size} aria-hidden="true" />;
    case 'PROCESSING':
      return <Loader2 size={size} className="spin-slow" aria-hidden="true" />;
    case 'RETRYING':
      return <RotateCw size={size} aria-hidden="true" />;
    case 'DELIVERED':
      return <Check size={size} aria-hidden="true" strokeWidth={2.5} />;
    case 'FAILED':
      return <X size={size} aria-hidden="true" strokeWidth={2.5} />;
    case 'DLQ':
      return <Archive size={size} aria-hidden="true" />;
    case 'CANCELLED':
      return <Ban size={size} aria-hidden="true" />;
    default:
      return <Layers size={size} aria-hidden="true" />;
  }
}

export function getStatusColor(status: NotificationStatus | RailStageName): { text: string; bg: string } {
  switch (status) {
    case 'SCHEDULED':
      return { text: 'var(--color-st-scheduled)', bg: 'var(--color-st-scheduled-bg)' };
    case 'CREATED':
    case 'QUEUED':
      return { text: 'var(--color-st-queued)', bg: 'var(--color-st-queued-bg)' };
    case 'PROCESSING':
      return { text: 'var(--color-st-processing)', bg: 'var(--color-st-processing-bg)' };
    case 'RETRYING':
      return { text: 'var(--color-st-retrying)', bg: 'var(--color-st-retrying-bg)' };
    case 'DELIVERED':
      return { text: 'var(--color-st-delivered)', bg: 'var(--color-st-delivered-bg)' };
    case 'FAILED':
      return { text: 'var(--color-st-failed)', bg: 'var(--color-st-failed-bg)' };
    case 'DLQ':
      return { text: 'var(--color-st-dlq)', bg: 'var(--color-st-dlq-bg)' };
    case 'CANCELLED':
      return { text: 'var(--color-st-cancelled)', bg: 'var(--color-st-cancelled-bg)' };
    default:
      return { text: 'var(--color-ink-2)', bg: 'var(--color-chalk)' };
  }
}

export const LifecycleRail: React.FC<LifecycleRailProps> = ({
  status,
  scheduledAt,
  queuedAt,
  processingStartedAt,
  completedAt,
  createdAt,
  attemptCount = 0,
  maxAttempts = 3,
  history = [],
  variant = 'full',
  aggregateCounts,
  onClickStage,
  className = '',
  style,
}) => {
  if (variant === 'compact') {
    const segments = computeCompactSegments(status, attemptCount);
    const { text, bg } = getStatusColor(status);

    return (
      <div
        className={`compact-rail-container ${className}`}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 8, ...style }}
        title={`Status: ${status}${attemptCount > 1 ? ` (${attemptCount} attempts)` : ''}`}
      >
        <svg
          width="96"
          height="6"
          viewBox="0 0 96 6"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden="true"
          style={{ flexShrink: 0 }}
        >
          {segments.map((color, idx) => (
            <rect
              key={idx}
              x={idx * 24}
              y="0"
              width="21"
              height="6"
              rx="3"
              fill={color}
            />
          ))}
        </svg>
        <span
          className="status-chip"
          style={{ color: text, backgroundColor: bg, padding: '2px 8px', fontSize: 11, fontWeight: 600 }}
        >
          {getStatusIcon(status, 12)}
          <span>{status}</span>
        </span>
      </div>
    );
  }

  if (variant === 'aggregate') {
    const aggregateStages: RailStageName[] = [
      'SCHEDULED',
      'QUEUED',
      'PROCESSING',
      'RETRYING',
      'DELIVERED',
      'FAILED',
      'DLQ',
    ];

    return (
      <div
        className={`aggregate-rail-container panel ${className}`}
        style={{ padding: '16px 20px', ...style }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', overflowX: 'auto', gap: 12 }}>
          {aggregateStages.map((st, i) => {
            const count = aggregateCounts?.[st] ?? 0;
            const colors = getStatusColor(st);
            return (
              <React.Fragment key={st}>
                <button
                  type="button"
                  onClick={() => onClickStage?.(st)}
                  className="aggregate-stage-node"
                  style={{
                    background: 'none',
                    border: 'none',
                    cursor: onClickStage ? 'pointer' : 'default',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 6,
                    minWidth: 80,
                    padding: '6px 8px',
                    borderRadius: 'var(--radius-control)',
                    transition: 'background-color 0.15s',
                  }}
                >
                  <span
                    className="mono"
                    style={{
                      fontSize: 18,
                      fontWeight: 700,
                      color: count > 0 ? colors.text : 'var(--color-ink-2)',
                    }}
                  >
                    {count.toLocaleString()}
                  </span>
                  <div
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 4,
                      fontSize: 11,
                      fontWeight: 600,
                      color: colors.text,
                      backgroundColor: colors.bg,
                      padding: '2px 6px',
                      borderRadius: 'var(--radius-chip)',
                    }}
                  >
                    {getStatusIcon(st, 11)}
                    <span>{st}</span>
                  </div>
                </button>
                {i < aggregateStages.length - 1 && (
                  <div style={{ flex: 1, height: 1, minWidth: 20, backgroundColor: 'var(--color-rail)' }} />
                )}
              </React.Fragment>
            );
          })}
        </div>
      </div>
    );
  }

  // Full Variant
  const railData = computeLifecycleStages({
    status,
    scheduledAt,
    queuedAt,
    processingStartedAt,
    completedAt,
    createdAt,
    attemptCount,
    history,
  });

  return (
    <div
      className={`lifecycle-rail-wrapper panel ${className}`}
      style={{ padding: '24px 20px', marginBottom: 20, ...style }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontWeight: 700, fontSize: 15, letterSpacing: '-0.01em', color: 'var(--color-harbor)' }}>
            The Dispatch Line
          </span>
          <span style={{ fontSize: 12, color: 'var(--color-ink-2)' }}>• Verified Lifecycle Progression</span>
        </div>
        {status === 'RETRYING' && (
          <span
            className="mono"
            style={{
              fontSize: 12,
              fontWeight: 600,
              color: 'var(--color-st-retrying)',
              backgroundColor: 'var(--color-st-retrying-bg)',
              padding: '3px 8px',
              borderRadius: 'var(--radius-control)',
            }}
          >
            Attempt {attemptCount} of {maxAttempts}
          </span>
        )}
      </div>

      <ol
        className="lifecycle-rail-track"
        aria-label="Notification lifecycle progress"
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          listStyle: 'none',
          padding: 0,
          margin: 0,
          overflowX: 'auto',
          width: '100%',
        }}
      >
        {railData.stages.map((stage, idx) => {
          const colors = getStatusColor(stage.name);
          const isCurrent = stage.state === 'current';
          const isDone = stage.state === 'done';
          const isUpcoming = stage.state === 'upcoming';
          const isSkipped = stage.state === 'skipped';
          const isCancelled = stage.name === 'CANCELLED';
          const isDlq = stage.name === 'DLQ';

          return (
            <React.Fragment key={stage.name}>
              <li
                aria-current={isCurrent ? 'step' : undefined}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  minWidth: 100,
                  position: 'relative',
                  flexShrink: 0,
                  opacity: isSkipped ? 0.6 : 1,
                }}
              >
                {/* Node circle */}
                <div
                  style={{
                    width: 32,
                    height: 32,
                    borderRadius: '50%',
                    display: 'grid',
                    placeItems: 'center',
                    backgroundColor: isCurrent
                      ? colors.bg
                      : isDone
                      ? 'var(--color-st-delivered-bg)'
                      : isSkipped
                      ? 'var(--color-chalk)'
                      : 'var(--color-paper)',
                    color: isCurrent
                      ? colors.text
                      : isDone
                      ? 'var(--color-st-delivered)'
                      : 'var(--color-rail)',
                    border: `2px ${isSkipped ? 'dashed' : 'solid'} ${
                      isCurrent
                        ? colors.text
                        : isDone
                        ? 'var(--color-st-delivered)'
                        : 'var(--color-rail)'
                    }`,
                    boxShadow: isCurrent
                      ? `0 0 0 4px ${colors.bg}, 0 0 12px ${colors.bg}`
                      : 'none',
                    transition: 'all 0.2s ease-out',
                    position: 'relative',
                  }}
                  className={isCurrent ? 'pulse-node' : ''}
                >
                  {getStageIcon(stage.name, stage.state, 16)}
                  {isDlq && (
                    <span
                      title="Dead-Letter Terminal Cap"
                      style={{
                        position: 'absolute',
                        right: -4,
                        top: 2,
                        width: 6,
                        height: 6,
                        borderRadius: '50%',
                        backgroundColor: 'var(--color-st-dlq)',
                      }}
                    />
                  )}
                </div>

                {/* Stage Label */}
                <span
                  style={{
                    fontSize: 13,
                    fontWeight: isCurrent ? 700 : 600,
                    color: isCurrent
                      ? colors.text
                      : isDone
                      ? 'var(--color-ink-1)'
                      : 'var(--color-ink-3)',
                    marginTop: 8,
                    textAlign: 'center',
                    textDecoration: isSkipped ? 'line-through' : 'none',
                  }}
                >
                  {stage.label}
                </span>

                {/* Timestamp */}
                <span
                  className="mono"
                  style={{
                    fontSize: 11,
                    color: 'var(--color-ink-2)',
                    marginTop: 3,
                    textAlign: 'center',
                  }}
                  title={stage.timestamp ? new Date(stage.timestamp).toUTCString() : undefined}
                >
                  {stage.relativeTime || (isSkipped ? 'skipped' : isUpcoming ? '—' : 'active')}
                </span>
              </li>

              {/* Connecting rail line */}
              {idx < railData.stages.length - 1 && (
                <div
                  style={{
                    flex: 1,
                    minWidth: 40,
                    height: 2,
                    marginTop: 15,
                    backgroundColor: isDone ? 'var(--color-st-delivered)' : 'var(--color-rail)',
                    position: 'relative',
                  }}
                >
                  {isCancelled && (
                    <div
                      style={{
                        position: 'absolute',
                        inset: 0,
                        backgroundColor: 'var(--color-st-cancelled)',
                        transform: 'rotate(12deg)',
                        height: 2,
                      }}
                    />
                  )}
                </div>
              )}
            </React.Fragment>
          );
        })}
      </ol>
    </div>
  );
};

export default LifecycleRail;
