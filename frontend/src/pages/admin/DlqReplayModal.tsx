import React, { useState, useEffect } from 'react';
import { AlertTriangle, X, RotateCw } from 'lucide-react';
import { replayDlqEntry } from '../../api/adminDlq';

export interface DlqReplayModalProps {
  entryId: string;
  tenantSlug: string;
  channel: string;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export default function DlqReplayModal({
  entryId,
  tenantSlug,
  channel,
  isOpen,
  onClose,
  onSuccess,
}: DlqReplayModalProps) {
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, submitting, onClose]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    try {
      await replayDlqEntry(entryId, note.trim() || undefined);
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err?.message || 'Failed to replay dead-letter entry.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="replay-modal-title"
      className="modal-overlay modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !submitting) onClose();
      }}
    >
      <div className="modal-card" style={{ maxWidth: 500 }}>
        {/* Header */}
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div
              style={{
                width: 30,
                height: 30,
                borderRadius: 'var(--radius-control)',
                backgroundColor: 'var(--color-st-processing-bg)',
                display: 'grid',
                placeItems: 'center',
                color: 'var(--color-signal)',
              }}
            >
              <RotateCw size={15} />
            </div>
            <h2 id="replay-modal-title" style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
              Replay Dead-Letter Notification
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="btn btn-ghost"
            style={{ padding: 4 }}
          >
            <X size={15} />
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {/* Target Info */}
            <div
              style={{
                display: 'flex',
                gap: 16,
                padding: '8px 12px',
                backgroundColor: 'var(--color-chalk)',
                borderRadius: 'var(--radius-control)',
                border: '1px solid var(--color-rail)',
                fontSize: 12,
              }}
            >
              <div>
                <span style={{ color: 'var(--color-ink-2)' }}>Tenant: </span>
                <strong className="mono" style={{ color: 'var(--color-harbor)' }}>{tenantSlug}</strong>
              </div>
              <div>
                <span style={{ color: 'var(--color-ink-2)' }}>Channel: </span>
                <strong style={{ color: 'var(--color-harbor)' }}>{channel}</strong>
              </div>
            </div>

            {/* Warning Banner */}
            <div
              style={{
                display: 'flex',
                gap: 10,
                padding: '12px 14px',
                backgroundColor: 'var(--color-st-retrying-bg)',
                border: '1px solid rgba(217, 119, 6, 0.3)',
                borderRadius: 'var(--radius-control)',
              }}
            >
              <AlertTriangle size={16} color="var(--color-st-retrying)" style={{ flexShrink: 0, marginTop: 2 }} />
              <div style={{ fontSize: 11, lineHeight: 1.5, color: 'var(--color-harbor)' }}>
                <strong>Duplicate Delivery Warning:</strong>
                <p style={{ margin: '4px 0 0', color: 'var(--color-ink-2)' }}>
                  Replaying re-enqueues this notification at attempt 0. If downstream providers or webhook destinations partially acknowledged a prior cycle, duplicate remote side effects may occur.
                </p>
              </div>
            </div>

            {/* Operator Note */}
            <div>
              <label
                htmlFor="dlq-replay-note"
                style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}
              >
                Operator Resolution Note <span style={{ fontWeight: 400, color: 'var(--color-ink-2)' }}>(optional)</span>
              </label>
              <textarea
                id="dlq-replay-note"
                rows={3}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="e.g. Destination endpoint service restored after downstream incident #491"
                disabled={submitting}
                maxLength={1024}
                className="input-text"
                style={{ fontSize: 12 }}
              />
            </div>

            {error && (
              <div
                style={{
                  padding: '8px 12px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  border: '1px solid var(--color-st-failed)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                }}
              >
                {error}
              </div>
            )}
          </div>

          {/* Actions */}
          <div className="modal-footer">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="btn btn-secondary"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="btn btn-primary"
            >
              {submitting ? 'Replaying…' : 'Replay Message'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}