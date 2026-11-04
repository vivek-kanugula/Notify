import type { NotificationStatus, NotificationHistoryEntry } from '../api/notifications';

export type RailStageName =
  | 'SCHEDULED'
  | 'QUEUED'
  | 'PROCESSING'
  | 'RETRYING'
  | 'DELIVERED'
  | 'FAILED'
  | 'DLQ'
  | 'CANCELLED';

export type StageState = 'done' | 'current' | 'upcoming' | 'skipped';

export interface ComputedStage {
  name: RailStageName;
  label: string;
  state: StageState;
  timestamp: string | null;
  relativeTime: string | null;
  attemptNumber?: number;
}

export interface LifecycleRailData {
  stages: ComputedStage[];
  isTerminal: boolean;
  terminalOutcome: 'DELIVERED' | 'FAILED' | 'DLQ' | 'CANCELLED' | null;
  retryCount: number;
  currentStatus: NotificationStatus;
}

const TERMINAL_STATUSES: Set<NotificationStatus> = new Set(['DELIVERED', 'FAILED', 'DLQ', 'CANCELLED']);

export function isTerminalStatus(status: NotificationStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

export function formatRelativeTime(isoString: string | null | undefined): string | null {
  if (!isoString) return null;
  const time = new Date(isoString).getTime();
  if (isNaN(time)) return null;
  const diffSec = Math.floor((Date.now() - time) / 1000);
  if (diffSec < 5) return 'just now';
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return `${diffHour}h ago`;
  const diffDays = Math.floor(diffHour / 24);
  return `${diffDays}d ago`;
}

/**
 * Computes canonical stages and active state based on status, timestamps, and history entries.
 */
export function computeLifecycleStages(input: {
  status: NotificationStatus;
  scheduledAt?: string | null;
  queuedAt?: string | null;
  processingStartedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string | null;
  attemptCount?: number;
  history?: NotificationHistoryEntry[];
}): LifecycleRailData {
  const { status, scheduledAt, queuedAt, processingStartedAt, completedAt, createdAt, attemptCount = 0, history = [] } = input;

  const historyMap = new Map<NotificationStatus, string>();
  for (const h of history) {
    if (h.toStatus) {
      historyMap.set(h.toStatus, h.occurredAt);
    }
  }

  const wasScheduled = Boolean(scheduledAt || status === 'SCHEDULED' || historyMap.has('SCHEDULED'));
  const stages: ComputedStage[] = [];

  // Stage 1: SCHEDULED (if applicable)
  if (wasScheduled) {
    let state: StageState = 'upcoming';
    if (status === 'SCHEDULED') {
      state = 'current';
    } else {
      state = 'done';
    }
    const ts = (historyMap.get('SCHEDULED') || scheduledAt || createdAt) ?? null;
    stages.push({
      name: 'SCHEDULED',
      label: 'Scheduled',
      state,
      timestamp: ts,
      relativeTime: formatRelativeTime(ts),
    });
  }

  // Stage 2: QUEUED
  {
    let state: StageState = 'upcoming';
    if (status === 'QUEUED') {
      state = 'current';
    } else if (
      status === 'PROCESSING' ||
      status === 'RETRYING' ||
      status === 'DELIVERED' ||
      status === 'FAILED' ||
      status === 'DLQ' ||
      (status === 'CANCELLED' && (historyMap.has('QUEUED') || queuedAt || !wasScheduled))
    ) {
      state = 'done';
    } else if (status === 'CANCELLED') {
      state = 'skipped';
    }
    const ts = (historyMap.get('QUEUED') || queuedAt || (!wasScheduled ? createdAt : null)) ?? null;
    stages.push({
      name: 'QUEUED',
      label: 'Queued',
      state,
      timestamp: ts,
      relativeTime: formatRelativeTime(ts),
    });
  }

  // Stage 3: PROCESSING / RETRYING
  {
    let state: StageState = 'upcoming';
    if (status === 'PROCESSING' || status === 'RETRYING') {
      state = 'current';
    } else if (
      status === 'DELIVERED' ||
      status === 'FAILED' ||
      status === 'DLQ' ||
      historyMap.has('PROCESSING') ||
      processingStartedAt
    ) {
      state = 'done';
    } else if (status === 'CANCELLED') {
      state = 'skipped';
    }
    const ts = (historyMap.get('PROCESSING') || processingStartedAt) ?? null;
    stages.push({
      name: status === 'RETRYING' ? 'RETRYING' : 'PROCESSING',
      label: status === 'RETRYING' ? `Retrying (x${Math.max(attemptCount, 1)})` : 'Processing',
      state,
      timestamp: ts,
      relativeTime: formatRelativeTime(ts),
      attemptNumber: attemptCount,
    });
  }

  // Stage 4: Terminal
  let terminalOutcome: 'DELIVERED' | 'FAILED' | 'DLQ' | 'CANCELLED' | null = null;
  if (isTerminalStatus(status)) {
    terminalOutcome = status as any;
  }

  const terminalName: RailStageName = terminalOutcome || 'DELIVERED';
  let terminalLabel = 'Delivered';
  if (terminalOutcome === 'FAILED') terminalLabel = 'Failed';
  else if (terminalOutcome === 'DLQ') terminalLabel = 'DLQ';
  else if (terminalOutcome === 'CANCELLED') terminalLabel = 'Cancelled';

  const terminalState: StageState = terminalOutcome ? 'current' : 'upcoming';
  const termTs = (completedAt || (terminalOutcome ? historyMap.get(terminalOutcome) : null)) ?? null;

  stages.push({
    name: terminalName,
    label: terminalLabel,
    state: terminalState,
    timestamp: termTs,
    relativeTime: formatRelativeTime(termTs),
  });

  return {
    stages,
    isTerminal: Boolean(terminalOutcome),
    terminalOutcome,
    retryCount: attemptCount > 1 ? attemptCount : (status === 'RETRYING' ? 1 : 0),
    currentStatus: status,
  };
}

/**
 * Segment colors for compact 96x6 bar: 4 segments.
 * Returns array of 4 CSS color strings.
 */
export function computeCompactSegments(status: NotificationStatus, attemptCount = 0): string[] {
  const railColor = 'var(--color-rail)';
  const scheduledColor = 'var(--color-st-scheduled)';
  const queuedColor = 'var(--color-st-queued)';
  const processingColor = 'var(--color-st-processing)';
  const retryingColor = 'var(--color-st-retrying)';
  const deliveredColor = 'var(--color-st-delivered)';
  const failedColor = 'var(--color-st-failed)';
  const dlqColor = 'var(--color-st-dlq)';
  const cancelledColor = 'var(--color-st-cancelled)';

  switch (status) {
    case 'SCHEDULED':
      return [scheduledColor, railColor, railColor, railColor];
    case 'CREATED':
    case 'QUEUED':
      return [queuedColor, railColor, railColor, railColor];
    case 'PROCESSING':
      return [queuedColor, processingColor, railColor, railColor];
    case 'RETRYING':
      return [queuedColor, processingColor, retryingColor, railColor];
    case 'DELIVERED':
      return [queuedColor, processingColor, attemptCount > 1 ? retryingColor : processingColor, deliveredColor];
    case 'FAILED':
      return [queuedColor, processingColor, retryingColor, failedColor];
    case 'DLQ':
      return [queuedColor, processingColor, retryingColor, dlqColor];
    case 'CANCELLED':
      return [queuedColor, cancelledColor, railColor, railColor];
    default:
      return [queuedColor, railColor, railColor, railColor];
  }
}

/**
 * Resolves visual state representation (icon type, completion flag, pulsing focus)
 * for a lifecycle stage. Done stages ALWAYS resolve to completed check state, never active spinners.
 */
export function resolveStageVisualState(stageName: RailStageName, state: StageState): {
  iconType: 'check' | 'spinner' | 'layers' | 'clock' | 'cross' | 'ban' | 'archive';
  isPulsing: boolean;
  isCompleted: boolean;
} {
  if (state === 'done') {
    return { iconType: 'check', isPulsing: false, isCompleted: true };
  }
  if (state === 'current') {
    let iconType: 'check' | 'spinner' | 'layers' | 'clock' | 'cross' | 'ban' | 'archive' = 'layers';
    if (stageName === 'PROCESSING') iconType = 'spinner';
    else if (stageName === 'DELIVERED') iconType = 'check';
    else if (stageName === 'FAILED') iconType = 'cross';
    else if (stageName === 'CANCELLED') iconType = 'ban';
    else if (stageName === 'DLQ') iconType = 'archive';
    else if (stageName === 'SCHEDULED') iconType = 'clock';
    return { iconType, isPulsing: true, isCompleted: false };
  }
  let iconType: 'check' | 'spinner' | 'layers' | 'clock' | 'cross' | 'ban' | 'archive' = 'layers';
  if (stageName === 'PROCESSING') iconType = 'spinner';
  else if (stageName === 'DELIVERED') iconType = 'check';
  return { iconType, isPulsing: false, isCompleted: false };
}
