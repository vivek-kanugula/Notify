export type SampleStage = 'QUEUED' | 'PROCESSING' | 'RETRYING' | 'DELIVERED';

export interface SampleStageMeta {
  name: SampleStage;
  label: string;
  timeOffset: string;
  text: string;
  bg: string;
}

export const SAMPLE_STAGES: SampleStageMeta[] = [
  {
    name: 'QUEUED',
    label: 'Queued',
    timeOffset: '0 ms',
    text: 'var(--color-st-queued)',
    bg: 'var(--color-st-queued-bg)',
  },
  {
    name: 'PROCESSING',
    label: 'Processing',
    timeOffset: '+12 ms',
    text: 'var(--color-st-processing)',
    bg: 'var(--color-st-processing-bg)',
  },
  {
    name: 'RETRYING',
    label: 'Retry ×2',
    timeOffset: '+24 ms',
    text: 'var(--color-st-retrying)',
    bg: 'var(--color-st-retrying-bg)',
  },
  {
    name: 'DELIVERED',
    label: 'Delivered',
    timeOffset: '+38 ms',
    text: 'var(--color-st-delivered)',
    bg: 'var(--color-st-delivered-bg)',
  },
];

export const SAMPLE_STAGE_DESCRIPTIONS: Record<SampleStage, string> = {
  QUEUED: 'Durable outbox acceptance · Committed to PostgreSQL in 2 ms',
  PROCESSING: 'Claimed by channel worker · Verification & template rendering',
  RETRYING: 'Provider returned 503 · Seeded exponential jitter attempt 2',
  DELIVERED: 'Provider 200 OK · Verified terminal delivery at-least-once',
};

/**
 * Checks if reduced motion is requested by the user's OS or browser.
 */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Determines the initial step based on reduced-motion preference.
 * If reduced motion is requested, immediately starts at the terminal Delivered state (step 3).
 */
export function getInitialSampleStep(isReducedMotion: boolean): number {
  return isReducedMotion ? 3 : 0;
}

/**
 * Advance to next sample step up to terminal index (3).
 */
export function advanceSampleStep(current: number): number {
  return Math.min(current + 1, 3);
}
