import React, { useState, useEffect } from 'react';
import { AlertOctagon, X } from 'lucide-react';

interface TenantDeleteModalProps {
  isOpen: boolean;
  onClose: () => void;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  onConfirm: (confirmSlug: string, reason?: string) => Promise<void>;
}

export default function TenantDeleteModal({
  isOpen,
  onClose,
  tenantId,
  tenantName,
  tenantSlug,
  onConfirm,
}: TenantDeleteModalProps) {
  const [typedSlug, setTypedSlug] = useState('');
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !loading) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, loading, onClose]);

  if (!isOpen) return null;

  const isSlugMatch = typedSlug.trim() === tenantSlug;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isSlugMatch) return;

    setLoading(true);
    setError(null);
    try {
      await onConfirm(typedSlug.trim(), reason.trim() || undefined);
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to delete tenant.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="delete-modal-title">
      <div className="modal-card" style={{ maxWidth: 480 }}>
        {/* Header */}
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 30, height: 30, borderRadius: 'var(--radius-control)', background: 'var(--color-st-failed-bg)', color: 'var(--color-st-failed)', display: 'grid', placeItems: 'center' }}>
              <AlertOctagon size={16} />
            </div>
            <h2 id="delete-modal-title" style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-st-failed)' }}>
              Confirm Tenant Deletion
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={loading}
            className="btn btn-ghost"
            style={{ padding: 4 }}
          >
            <X size={15} />
          </button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit}>
          <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div
              style={{
                padding: '12px 14px',
                backgroundColor: 'var(--color-chalk)',
                borderRadius: 'var(--radius-control)',
                fontSize: 12,
                color: 'var(--color-harbor)',
                borderLeft: '3px solid var(--color-st-failed)',
              }}
            >
              <div style={{ fontWeight: 700, color: 'var(--color-st-failed)', marginBottom: 4 }}>
                Danger: Irreversible Tenant Destruction
              </div>
              <div style={{ fontSize: 11, color: 'var(--color-ink-2)', lineHeight: 1.5 }}>
                Deleting <strong>{tenantName}</strong> (<span className="mono">{tenantSlug}</span>, ID: <span className="mono">{tenantId}</span>) will permanently remove all associated users, API keys, templates, and webhook configs. Audit records remain preserved.
              </div>
            </div>

            {error && (
              <div
                style={{
                  padding: '9px 13px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                  borderRadius: 'var(--radius-control)',
                  border: '1px solid var(--color-st-failed)',
                }}
              >
                {error}
              </div>
            )}

            <div>
              <label
                htmlFor="slug-confirm-input"
                style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}
              >
                Type slug <code className="mono" style={{ backgroundColor: 'var(--color-chalk)', padding: '2px 6px', borderRadius: 4 }}>{tenantSlug}</code> to confirm:
              </label>
              <input
                id="slug-confirm-input"
                type="text"
                className="input-text mono"
                value={typedSlug}
                onChange={(e) => setTypedSlug(e.target.value)}
                placeholder={tenantSlug}
                required
                autoFocus
                style={{
                  fontSize: 12,
                  borderColor: isSlugMatch ? 'var(--color-st-failed)' : undefined,
                }}
              />
            </div>

            <div>
              <label
                htmlFor="delete-reason-input"
                style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}
              >
                Audit Reason (Optional)
              </label>
              <input
                id="delete-reason-input"
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Requested account closure, fraudulent signup..."
                className="input-text"
                style={{ fontSize: 12 }}
              />
            </div>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={loading}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading || !isSlugMatch}
              className="btn btn-danger"
              style={{
                opacity: !isSlugMatch ? 0.45 : 1,
              }}
            >
              {loading ? 'Deleting…' : 'Delete Tenant Permanently'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}