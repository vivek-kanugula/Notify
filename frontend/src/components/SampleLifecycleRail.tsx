import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Layers, Loader2, RotateCw, Check, RotateCcw } from 'lucide-react';
import {
  SAMPLE_STAGES,
  SAMPLE_STAGE_DESCRIPTIONS,
  prefersReducedMotion,
  getInitialSampleStep,
  type SampleStage,
} from './sampleRailLogic';

function getSampleStageIcon(stage: SampleStage) {
  switch (stage) {
    case 'QUEUED':
      return <Layers size={16} aria-hidden="true" />;
    case 'PROCESSING':
      return <Loader2 size={16} className="spin-slow" aria-hidden="true" />;
    case 'RETRYING':
      return <RotateCw size={16} aria-hidden="true" />;
    case 'DELIVERED':
      return <Check size={16} strokeWidth={2.5} aria-hidden="true" />;
  }
}

/**
 * Sample Lifecycle Rail
 * Plays the sample sequence (Queued → Processing → Retry ×2 → Delivered) once on page load.
 * On prefers-reduced-motion: reduce, immediately renders the static terminal Delivered state.
 */
export default function SampleLifecycleRail({ className = '' }: { className?: string }) {
  // Start at terminal state (3) if reduced motion is preferred, otherwise start at 0
  const [currentStep, setCurrentStep] = useState<number>(() => {
    return getInitialSampleStep(prefersReducedMotion());
  });
  const [isAnimating, setIsAnimating] = useState<boolean>(() => {
    return !prefersReducedMotion();
  });

  const timersRef = useRef<number[]>([]);

  const clearTimers = useCallback(() => {
    timersRef.current.forEach((t) => window.clearTimeout(t));
    timersRef.current = [];
  }, []);

  const playSequence = useCallback(() => {
    clearTimers();

    if (prefersReducedMotion()) {
      setCurrentStep(3);
      setIsAnimating(false);
      return;
    }

    setCurrentStep(0);
    setIsAnimating(true);

    const t1 = window.setTimeout(() => setCurrentStep(1), 750);
    const t2 = window.setTimeout(() => setCurrentStep(2), 1500);
    const t3 = window.setTimeout(() => {
      setCurrentStep(3);
      setIsAnimating(false);
    }, 2300);

    timersRef.current = [t1, t2, t3];
  }, [clearTimers]);

  useEffect(() => {
    playSequence();
    return () => clearTimers();
  }, [playSequence, clearTimers]);

  const activeStageName = SAMPLE_STAGES[currentStep].name;

  return (
    <div
      className={`sample-lifecycle-rail ${className}`}
      role="region"
      aria-label="Sample notification delivery lifecycle"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 16,
        width: '100%',
      }}
    >
      {/* Rail Header with status badge and replay button */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontWeight: 700, fontSize: 13, letterSpacing: '0.04em', textTransform: 'uppercase', color: 'var(--color-ink-2)' }}>
            The Dispatch Line
          </span>
          <span
            className="status-chip"
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: SAMPLE_STAGES[currentStep].text,
              backgroundColor: SAMPLE_STAGES[currentStep].bg,
              padding: '2px 8px',
              borderRadius: 'var(--radius-chip)',
            }}
          >
            {SAMPLE_STAGES[currentStep].label}
          </span>
        </div>

        <button
          type="button"
          onClick={playSequence}
          disabled={isAnimating}
          aria-label="Replay lifecycle sample"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            background: 'none',
            border: 'none',
            color: isAnimating ? 'var(--color-rail)' : 'var(--color-signal)',
            fontSize: 12,
            fontWeight: 600,
            cursor: isAnimating ? 'default' : 'pointer',
            padding: '4px 8px',
            borderRadius: 'var(--radius-control)',
          }}
        >
          <RotateCcw size={13} className={isAnimating ? 'spin-slow' : ''} aria-hidden="true" />
          <span>{isAnimating ? 'Playing…' : 'Replay'}</span>
        </button>
      </div>

      {/* Rail Step Sequence Track */}
      <ol
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          listStyle: 'none',
          padding: 0,
          margin: 0,
          position: 'relative',
          width: '100%',
        }}
      >
        {SAMPLE_STAGES.map((st, idx) => {
          const isDone = idx < currentStep;
          const isCurrent = idx === currentStep;
          const isUpcoming = idx > currentStep;

          return (
            <React.Fragment key={st.name}>
              <li
                aria-current={isCurrent ? 'step' : undefined}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  zIndex: 2,
                  minWidth: 70,
                }}
              >
                {/* Stage Node Circle */}
                <div
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: '50%',
                    display: 'grid',
                    placeItems: 'center',
                    backgroundColor: isDone || isCurrent ? st.bg : 'var(--color-paper)',
                    color: isDone || isCurrent ? st.text : 'var(--color-rail)',
                    border: `2px solid ${isDone || isCurrent ? st.text : 'var(--color-rail)'}`,
                    boxShadow: isCurrent ? `0 0 0 4px ${st.bg}` : 'none',
                    transition: 'all 0.25s ease-out',
                  }}
                  className={isCurrent && isAnimating ? 'pulse-node' : ''}
                >
                  {getSampleStageIcon(st.name)}
                </div>

                {/* Node Label */}
                <span
                  style={{
                    fontSize: 12,
                    fontWeight: isCurrent ? 700 : 500,
                    color: isCurrent ? 'var(--color-harbor)' : isDone ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                    marginTop: 8,
                    textAlign: 'center',
                  }}
                >
                  {st.label}
                </span>

                {/* Relative timestamp */}
                <span
                  className="mono"
                  style={{
                    fontSize: 11,
                    color: isUpcoming ? 'var(--color-rail)' : 'var(--color-ink-2)',
                    marginTop: 2,
                  }}
                >
                  {st.timeOffset}
                </span>
              </li>

              {/* Connecting Line between nodes */}
              {idx < SAMPLE_STAGES.length - 1 && (
                <div
                  style={{
                    flex: 1,
                    height: 2,
                    margin: '0 4px',
                    marginBottom: 36,
                    backgroundColor: idx < currentStep ? 'var(--color-solid-active, var(--color-harbor))' : 'var(--color-rail)',
                    transition: 'background-color 0.3s ease-out',
                  }}
                  aria-hidden="true"
                />
              )}
            </React.Fragment>
          );
        })}
      </ol>

      {/* Explanatory Step Description */}
      <div
        style={{
          borderTop: '1px solid var(--color-rail)',
          paddingTop: 12,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          fontSize: 12,
          color: 'var(--color-ink-2)',
        }}
      >
        <span className="mono" style={{ color: 'var(--color-harbor)', fontWeight: 500 }}>
          {SAMPLE_STAGE_DESCRIPTIONS[activeStageName]}
        </span>
        <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
          Sample execution
        </span>
      </div>
    </div>
  );
}
