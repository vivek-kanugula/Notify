import React from 'react';
import { AlertTriangle, ShieldOff } from 'lucide-react';

export interface TenantStatusBannerProps {
  status: string | null | undefined;
  className?: string;
}

export const TenantStatusBanner: React.FC<TenantStatusBannerProps> = ({
  status,
  className = '',
}) => {
  if (!status || status === 'ACTIVE') {
    return null;
  }

  const isRevoked = status === 'REVOKED';

  const bgColor = isRevoked ? 'var(--color-st-failed-bg)' : 'var(--color-st-retrying-bg)';
  const borderColor = isRevoked ? 'var(--color-st-failed)' : 'var(--color-st-retrying)';
  const textColor = isRevoked ? 'var(--color-st-failed)' : 'var(--color-st-retrying)';

  return (
    <div
      className={`tenant-status-banner panel ${className}`}
      style={{
        backgroundColor: bgColor,
        borderColor: borderColor,
        color: textColor,
        padding: '12px 20px',
        marginBottom: 16,
        display: 'flex',
        alignItems: 'center',
        gap: 12,
      }}
    >
      {isRevoked ? (
        <ShieldOff size={20} style={{ flexShrink: 0 }} />
      ) : (
        <AlertTriangle size={20} style={{ flexShrink: 0 }} />
      )}
      <div>
        <strong style={{ fontSize: 14 }}>
          {isRevoked ? 'Tenant Account Revoked:' : 'Tenant Account Suspended:'}
        </strong>{' '}
        <span style={{ fontSize: 13 }}>
          {isRevoked
            ? 'Access to this workspace has been permanently revoked. Notification ingress, API keys, and outbound dispatches are refused.'
            : 'This workspace is currently suspended by Notify administration. All write operations, notification acceptance, and dispatch relays are temporarily disabled.'}
        </span>
      </div>
    </div>
  );
};

export default TenantStatusBanner;
