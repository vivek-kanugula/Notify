import React, { useState } from 'react';
import { Copy, Check, AlertTriangle, ShieldCheck } from 'lucide-react';

export interface OneTimeSecretModalProps {
  title: string;
  secret: string;
  keyName?: string;
  warningText?: string;
  onClose: () => void;
}

export const OneTimeSecretModal: React.FC<OneTimeSecretModalProps> = ({
  title,
  secret,
  keyName,
  warningText = 'Copy this secret key now. It will never be displayed again.',
  onClose,
}) => {
  const [copied, setCopied] = useState(false);
  const [hasConfirmed, setHasConfirmed] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      setHasConfirmed(true);
      setTimeout(() => {
        setCopied(false);
      }, 2500);
    } catch {
      // Fallback for non-secure origins: still mark confirmed so user can copy manually
      setHasConfirmed(true);
    }
  };

  return (
    <div className="modal-backdrop">
      <div className="modal-card" style={{ maxWidth: 480 }}>
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 32, height: 32, borderRadius: 'var(--radius-control)', background: 'var(--color-st-delivered-bg)', color: 'var(--color-st-delivered)', display: 'grid', placeItems: 'center' }}>
              <ShieldCheck size={18} />
            </div>
            <div>
              <h3 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
                {title}
              </h3>
              {keyName && (
                <span style={{ fontSize: 12, color: 'var(--color-ink-2)' }}>
                  Identifier: <strong style={{ color: 'var(--color-harbor)' }}>{keyName}</strong>
                </span>
              )}
            </div>
          </div>
        </div>

        <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* Warning Banner */}
          <div
            style={{
              padding: '10px 14px',
              backgroundColor: 'var(--color-st-retrying-bg)',
              border: '1px solid rgba(217, 119, 6, 0.3)',
              borderRadius: 'var(--radius-control)',
              color: 'var(--color-st-retrying)',
              fontSize: 12,
              fontWeight: 500,
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              lineHeight: 1.5,
            }}
          >
            <AlertTriangle size={16} style={{ flexShrink: 0 }} />
            <span>{warningText}</span>
          </div>

          {/* Secret Value Display Box */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: 'var(--color-chalk)',
              border: '1px solid var(--color-rail)',
              borderRadius: 'var(--radius-control)',
              padding: '10px 12px',
              gap: 10,
            }}
          >
            <span
              className="mono"
              style={{
                fontSize: 12,
                fontWeight: 600,
                color: 'var(--color-harbor)',
                wordBreak: 'break-all',
                userSelect: 'all',
                lineHeight: 1.5,
              }}
            >
              {secret}
            </span>

            <button
              type="button"
              onClick={handleCopy}
              className="btn btn-secondary"
              style={{
                padding: '6px 12px',
                fontSize: 12,
                flexShrink: 0,
                color: copied ? 'var(--color-st-delivered)' : undefined,
              }}
            >
              {copied ? <Check size={13} /> : <Copy size={13} />}
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>
          </div>

          {/* Safety Barrier Checkbox */}
          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 9,
              fontSize: 12,
              cursor: 'pointer',
              color: 'var(--color-harbor)',
              userSelect: 'none',
              marginTop: 4,
            }}
          >
            <input
              type="checkbox"
              checked={hasConfirmed}
              onChange={(e) => setHasConfirmed(e.target.checked)}
              style={{ cursor: 'pointer' }}
            />
            <span>I have securely copied and stored this secret key.</span>
          </label>
        </div>

        <div className="modal-footer">
          <button
            type="button"
            disabled={!hasConfirmed}
            onClick={onClose}
            className="btn btn-primary"
            style={{
              opacity: hasConfirmed ? 1 : 0.45,
              cursor: hasConfirmed ? 'pointer' : 'not-allowed',
            }}
          >
            I've copied it
          </button>
        </div>
      </div>
    </div>
  );
};

export default OneTimeSecretModal;