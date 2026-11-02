import React, { useState, useEffect } from 'react';
import { AlertOctagon, X, Trash2 } from 'lucide-react';
import { discardDlqEntry } from '../../api/adminDlq';

export interface DlqDiscardModalProps {
  entryId: string;
  tenantSlug: string;
  channel: string;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export default function DlqDiscardModal({
  entryId,
  tenantSlug,
  channel,
  isOpen,
  onClose,
  onSuccess,
}: DlqDiscardModalProps) {
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

  const isNoteValid = note.trim().length >= 3;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isNoteValid) {
      setError('A discard note of at least 3 characters is mandatory.');
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await discardDlqEntry(entryId, note.trim());
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err?.message || 'Failed to discard dead-letter entry.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="discard-modal-title"
      className="modal-backdrop"
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
                backgroundColor: 'var(--color-st-failed-bg)',
                display: 'grid',
                placeItems: 'center',
                color: 'var(--color-st-failed)',
              }}
            >
              <AlertOctagon size={16} />
            </div>
            <h2 id="discard-modal-title" style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-st-failed)' }}>
              Discard Dead-Letter Notification
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

            {/* Danger Warning Banner */}
            <div
              style={{
                display: 'flex',
                gap: 10,
                padding: '12px 14px',
                backgroundColor: 'var(--color-st-failed-bg)',
                border: '1px solid var(--color-st-failed)',
                borderRadius: 'var(--radius-control)',
              }}
            >
              <AlertOctagon size={16} color="var(--color-st-failed)" style={{ flexShrink: 0, marginTop: 2 }} />
              <div style={{ fontSize: 11, lineHeight: 1.5, color: 'var(--color-harbor)' }}>
                <strong style={{ color: 'var(--color-st-failed)' }}>Permanent Termination:</strong>
                <p style={{ margin: '4px 0 0', color: 'var(--color-ink-2)' }}>
                  Discarding resolves this dead-letter entry permanently. The notification will stay in terminal
                  <strong> FAILED</strong> status and will never be retried or dispatched again.
                </p>
              </div>
            </div>

            {/* Mandatory Operator Note */}
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                <label
                  htmlFor="dlq-discard-note"
                  style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}
                >
                  Mandatory Discard Reason <span style={{ color: 'var(--color-st-failed)' }}>*</span>
                </label>
                <span
                  className="mono"
                  style={{
                    fontSize: 10,
                    color: isNoteValid ? 'var(--color-ink-2)' : 'var(--color-st-failed)',
                  }}
                >
                  {note.trim().length}/1024 (min 3)
                </span>
              </div>
              <textarea
                id="dlq-discard-note"
                rows={3}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="e.g. Unrecoverable bad recipient address / corrupted payload verified by support"
                disabled={submitting}
                maxLength={1024}
                required
                className="input-text"
                style={{
                  fontSize: 12,
                  borderColor: note.length > 0 && !isNoteValid ? 'var(--color-st-failed)' : undefined,
                }}
              />
              {note.length > 0 && !isNoteValid && (
                <span style={{ fontSize: 11, color: 'var(--color-st-failed)', marginTop: 4, display: 'block' }}>
                  Reason must be at least 3 characters.
                </span>
              )}
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
              disabled={submitting || !isNoteValid}
              className="btn btn-danger"
              style={{
                opacity: !isNoteValid ? 0.45 : 1,
              }}
            >
              <Trash2 size={13} />
              <span>{submitting ? 'Discarding…' : 'Discard Message'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}