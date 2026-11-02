import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { Copy, Check, Shield, LogOut, Building, Gauge, AlertTriangle } from 'lucide-react';

export default function SettingsPage() {
  const { user, logout } = useAuth();
  const [copiedTenantId, setCopiedTenantId] = useState(false);
  const [signingOutAll, setSigningOutAll] = useState(false);
  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);

  const handleCopyTenantId = async () => {
    if (user?.tenantId) {
      try {
        await navigator.clipboard.writeText(user.tenantId);
        setCopiedTenantId(true);
        setTimeout(() => setCopiedTenantId(false), 2000);
      } catch {
        // Fallback for non-secure contexts
      }
    }
  };

  const handleSignOut = async () => {
    try {
      setSigningOutAll(true);
      // Ensure bearer token is passed during logout so JTI is denylisted
      await logout();
      window.location.assign('/login');
    } catch {
      window.location.assign('/login');
    } finally {
      setSigningOutAll(false);
      setShowSignOutConfirm(false);
    }
  };

  return (
    <div className="page-container" style={{ maxWidth: 960 }}>
      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)', margin: '0 0 6px' }}>
          Workspace Settings
        </h1>
        <p style={{ color: 'var(--color-ink-2)', fontSize: 13, margin: 0 }}>
          Manage organization identity, multi-tenant isolation parameters, ingress rate quotas, and session credentials.
        </p>
      </div>

      {/* Organization Profile Card */}
      <div className="panel" style={{ padding: 22, marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <div style={{ width: 28, height: 28, borderRadius: 'var(--radius-control)', background: 'var(--color-st-processing-bg)', color: 'var(--color-signal)', display: 'grid', placeItems: 'center' }}>
            <Building size={15} />
          </div>
          <h2 style={{ fontSize: 14, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
            Organization Profile
          </h2>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 16 }}>
          <div>
            <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)', marginBottom: 4 }}>
              Organization Name
            </label>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-harbor)' }}>
              {user?.tenantName || 'Workspace'}
            </div>
          </div>

          <div>
            <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)', marginBottom: 4 }}>
              Account Owner Email
            </label>
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-harbor)' }}>
              {user?.email || '—'}
            </div>
          </div>

          <div style={{ gridColumn: '1 / -1' }}>
            <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-ink-2)', marginBottom: 6 }}>
              Tenant Identifier
            </label>
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                backgroundColor: 'var(--color-chalk)',
                border: '1px solid var(--color-rail)',
                padding: '6px 12px',
                borderRadius: 'var(--radius-control)',
              }}
            >
              <span className="mono" style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                {user?.tenantId || '—'}
              </span>
              <button
                type="button"
                onClick={handleCopyTenantId}
                title="Copy Tenant ID"
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                {copiedTenantId ? <Check size={13} style={{ color: 'var(--color-st-delivered)' }} /> : <Copy size={13} style={{ color: 'var(--color-signal)' }} />}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Plan Limits & Rate Quotas */}
      <div className="panel" style={{ padding: 22, marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <div style={{ width: 28, height: 28, borderRadius: 'var(--radius-control)', background: 'var(--color-st-processing-bg)', color: 'var(--color-signal)', display: 'grid', placeItems: 'center' }}>
            <Gauge size={15} />
          </div>
          <h2 style={{ fontSize: 14, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
            Plan Limits & Ingress Quota
          </h2>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 14 }}>
          <div style={{ backgroundColor: 'var(--color-chalk)', padding: 14, borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)', display: 'block', marginBottom: 4 }}>
              Ingress Rate Limit
            </span>
            <div className="mono" style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-harbor)' }}>
              100 req / min
            </div>
            <span style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'block' }}>
              Sliding window enforcement via Redis
            </span>
          </div>

          <div style={{ backgroundColor: 'var(--color-chalk)', padding: 14, borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)', display: 'block', marginBottom: 4 }}>
              Maximum Active API Keys
            </span>
            <div className="mono" style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-harbor)' }}>
              10 keys
            </div>
            <span style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'block' }}>
              Enforced at key generation
            </span>
          </div>

          <div style={{ backgroundColor: 'var(--color-chalk)', padding: 14, borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink-2)', display: 'block', marginBottom: 4 }}>
              Workspace Status
            </span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span
                className={`chip ${user?.tenantStatus === 'ACTIVE' ? 'chip-delivered' : 'chip-cancelled'}`}
                style={{ fontSize: 11, padding: '2px 8px' }}
              >
                {user?.tenantStatus || 'UNKNOWN'}
              </span>
            </div>
            <span style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'block' }}>
              Checked dynamically per API call
            </span>
          </div>
        </div>
      </div>

      {/* Security & Session Controls */}
      <div className="panel" style={{ padding: 22 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <div style={{ width: 28, height: 28, borderRadius: 'var(--radius-control)', background: 'var(--color-st-processing-bg)', color: 'var(--color-signal)', display: 'grid', placeItems: 'center' }}>
            <Shield size={15} />
          </div>
          <h2 style={{ fontSize: 14, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
            Session & Security Architecture
          </h2>
        </div>

        <p style={{ fontSize: 12, color: 'var(--color-ink-2)', lineHeight: 1.6, margin: '0 0 16px' }}>
          All access tokens reside strictly in memory and are never persisted to localStorage. Refresh tokens are secured in HttpOnly cookies with automatic single-use rotation and replay detection.
        </p>

        {showSignOutConfirm ? (
          <div
            style={{
              padding: 14,
              borderRadius: 'var(--radius-control)',
              backgroundColor: 'var(--color-st-failed-bg)',
              border: '1px solid var(--color-st-failed)',
              maxWidth: 480,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, color: 'var(--color-st-failed)', fontWeight: 600, fontSize: 13 }}>
              <AlertTriangle size={15} />
              <span>Sign out of active session?</span>
            </div>
            <p style={{ fontSize: 12, color: 'var(--color-ink-2)', margin: '0 0 12px', lineHeight: 1.5 }}>
              Your in-memory token will be denylisted and your active refresh token family revoked immediately.
            </p>
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                onClick={handleSignOut}
                disabled={signingOutAll}
                className="btn btn-danger"
                style={{ fontSize: 12 }}
              >
                {signingOutAll ? 'Signing out…' : 'Confirm Sign Out'}
              </button>
              <button
                type="button"
                onClick={() => setShowSignOutConfirm(false)}
                disabled={signingOutAll}
                className="btn btn-secondary"
                style={{ fontSize: 12 }}
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setShowSignOutConfirm(true)}
            disabled={signingOutAll}
            className="btn btn-secondary"
            style={{ fontSize: 12, color: 'var(--color-st-failed)' }}
          >
            <LogOut size={13} />
            Sign Out of Current Session
          </button>
        )}
      </div>
    </div>
  );
}