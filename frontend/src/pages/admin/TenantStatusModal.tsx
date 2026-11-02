import React, { useState, useEffect } from 'react';
import type { TenantStatus } from '../../api/adminTenants';
import { AlertTriangle, CheckCircle2, ShieldAlert, X } from 'lucide-react';

interface TenantStatusModalProps {
  isOpen: boolean;
  onClose: () => void;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  currentStatus: TenantStatus;
  targetStatus: TenantStatus;
  onConfirm: (reason: string) => Promise<void>;
}

export default function TenantStatusModal({
  isOpen,
  onClose,
  tenantId,
  tenantName,
  tenantSlug,
  currentStatus,
  targetStatus,
  onConfirm,
}: TenantStatusModalProps) {
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

  const isDestructive = targetStatus === 'SUSPENDED' || targetStatus === 'REVOKED';

  const getConsequenceText = () => {
    switch (targetStatus) {
      case 'SUSPENDED':
        return 'Tenant API keys and active tokens will immediately reject with 403 TENANT_SUSPENDED. The tenant dashboard will be locked into read-only mode. In-flight notifications will fail and route to the Dead-Letter Queue.';
      case 'REVOKED':
        return 'Permanent revocation cascade: All API keys and active tenant sessions will be immediately invalidated and cascade-revoked. Historical delivery records and audit logs are immutably preserved.';
      case 'ACTIVE':
        return 'Reactivates tenant ingress. If previously revoked, existing keys remain revoked and a new API key must be provisioned.';
      default:
        return '';
    }
  };

  const getTitle = () => {
    switch (targetStatus) {
      case 'SUSPENDED':
        return 'Suspend Tenant Access';
      case 'REVOKED':
        return 'Revoke Tenant Access';
      case 'ACTIVE':
        return 'Reactivate Tenant Access';
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (reason.trim().length < 3) {
      setError('A descriptive reason of at least 3 characters is strictly required.');
      return;
    }

    setLoading(true);
    setError(null);
    try {
      await onConfirm(reason.trim());
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to update tenant status.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="status-modal-title">
      <div className="modal-card" style={{ maxWidth: 480 }}>
        {/* Header */}
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div
              style={{
                width: 30,
                height: 30,
                borderRadius: 'var(--radius-control)',
                background: targetStatus === 'REVOKED'
                  ? 'var(--color-st-failed-bg)'
                  : targetStatus === 'SUSPENDED'
                  ? 'var(--color-st-retrying-bg)'
                  : 'var(--color-st-delivered-bg)',
                color: targetStatus === 'REVOKED'
                  ? 'var(--color-st-failed)'
                  : targetStatus === 'SUSPENDED'
                  ? 'var(--color-st-retrying)'
                  : 'var(--color-st-delivered)',
                display: 'grid',
                placeItems: 'center',
              }}
            >
              {isDestructive ? <AlertTriangle size={16} /> : <CheckCircle2 size={16} />}
            </div>
            <h2 id="status-modal-title" style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
              {getTitle()}
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
                borderLeft: `3px solid ${
                  targetStatus === 'REVOKED'
                    ? 'var(--color-st-failed)'
                    : targetStatus === 'SUSPENDED'
                    ? 'var(--color-st-retrying)'
                    : 'var(--color-st-delivered)'
                }`,
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: 4 }}>
                Target: {tenantName} <span className="mono" style={{ color: 'var(--color-ink-2)' }}>({tenantSlug})</span> · ID: <span className="mono" style={{ color: 'var(--color-ink-2)' }}>{tenantId}</span>
              </div>
              <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginBottom: 6 }}>
                Status: <strong>{currentStatus}</strong> → Transitioning to <strong style={{ color: 'var(--color-harbor)' }}>{targetStatus}</strong>
              </div>
              <div style={{ fontSize: 11, color: 'var(--color-ink-2)', lineHeight: 1.5 }}>
                {getConsequenceText()}
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
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <ShieldAlert size={14} />
                <span>{error}</span>
              </div>
            )}

            <div>
              <label
                htmlFor="status-reason-input"
                style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}
              >
                Administrative Reason <span style={{ color: 'var(--color-st-failed)' }}>*</span>
              </label>
              <textarea
                id="status-reason-input"
                rows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Terms of Service violation, suspicious outbound volume spike, operator maintenance..."
                required
                className="input-text"
                style={{ fontSize: 12, lineHeight: 1.5 }}
              />
              <span style={{ fontSize: 11, color: 'var(--color-ink-2)', display: 'block', marginTop: 4 }}>
                This reason is immutably recorded in the platform security audit log.
              </span>
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
              disabled={loading || reason.trim().length < 3}
              className={`btn ${
                targetStatus === 'REVOKED'
                  ? 'btn-danger'
                  : targetStatus === 'SUSPENDED'
                  ? 'btn-secondary'
                  : 'btn-primary'
              }`}
              style={{
                backgroundColor: targetStatus === 'SUSPENDED' ? 'var(--color-st-retrying)' : undefined,
                color: targetStatus === 'SUSPENDED' ? '#FFFFFF' : undefined,
                borderColor: targetStatus === 'SUSPENDED' ? 'var(--color-st-retrying)' : undefined,
              }}
            >
              {loading ? 'Updating…' : getTitle()}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}