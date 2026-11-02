import { useState, useEffect } from 'react';
import { useAuth } from '../auth/AuthContext';
import {
  listApiKeys,
  createApiKey,
  revokeApiKey,
  rotateApiKey,
  type ApiKey,
  type ApiKeyCreated,
} from '../api/apiKeys';
import { AVAILABLE_SCOPES, formatExpiry } from './apiKeysLogic';
import { OneTimeSecretModal } from '../components/OneTimeSecretModal';
import {
  Shield,
  Key,
  Plus,
  RefreshCw,
  Trash2,
  AlertCircle,
  Terminal,
  X,
} from 'lucide-react';

export default function ApiKeysPage() {
  const { user } = useAuth();
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // Modals state
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showRevealModal, setShowRevealModal] = useState(false);
  const [revealedKey, setRevealedKey] = useState<ApiKeyCreated | null>(null);

  // Form state
  const [keyName, setKeyName] = useState('');
  const [selectedScopes, setSelectedScopes] = useState<string[]>(['notifications:send', 'notifications:read']);
  const [expiresAt, setExpiresAt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Revoke confirm state
  const [keyToRevoke, setKeyToRevoke] = useState<ApiKey | null>(null);
  const [revoking, setRevoking] = useState(false);

  // Rotate state
  const [keyToRotate, setKeyToRotate] = useState<ApiKey | null>(null);
  const [gracePeriodHours, setGracePeriodHours] = useState(24);
  const [rotating, setRotating] = useState(false);

  const isSuspended = user?.tenantStatus === 'SUSPENDED';
  const isOwner = Boolean(
    (user as any)?.role === 'TENANT_OWNER' ||
    (user as any)?.role === 'ROLE_TENANT_OWNER' ||
    user?.roles?.some((r) => r === 'TENANT_OWNER' || r === 'ROLE_TENANT_OWNER')
  );

  const fetchKeys = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await listApiKeys();
      setKeys(data);
    } catch (err: any) {
      setError(err.message || 'Failed to load API keys');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (user && !isOwner) {
      setLoading(false);
      return;
    }
    fetchKeys();
  }, [user, isOwner]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!keyName.trim()) {
      setFormError('Key name is required');
      return;
    }
    if (selectedScopes.length === 0) {
      setFormError('At least one scope must be selected');
      return;
    }

    try {
      setSubmitting(true);
      setFormError(null);
      const res = await createApiKey({
        name: keyName.trim(),
        scopes: selectedScopes,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
      });
      setShowCreateModal(false);
      setRevealedKey(res);
      setShowRevealModal(true);
      // Reset form
      setKeyName('');
      setSelectedScopes(['notifications:send', 'notifications:read']);
      setExpiresAt('');
      await fetchKeys();
    } catch (err: any) {
      setFormError(err.message || 'Failed to create key');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRotateConfirm = async () => {
    if (!keyToRotate) return;
    try {
      setRotating(true);
      setActionError(null);
      const res = await rotateApiKey(keyToRotate.id, {
        name: keyToRotate.name,
        scopes: keyToRotate.scopes,
        gracePeriodHours,
      });
      setKeyToRotate(null);
      setRevealedKey(res);
      setShowRevealModal(true);
      await fetchKeys();
    } catch (err: any) {
      setActionError(`Rotation failed: ${err.message || 'Unknown error'}`);
    } finally {
      setRotating(false);
    }
  };

  const handleRevokeConfirm = async () => {
    if (!keyToRevoke) return;
    try {
      setRevoking(true);
      setActionError(null);
      await revokeApiKey(keyToRevoke.id);
      setKeyToRevoke(null);
      await fetchKeys();
    } catch (err: any) {
      setActionError(`Revocation failed: ${err.message || 'Unknown error'}`);
    } finally {
      setRevoking(false);
    }
  };

  const renderStatusBadge = (k: ApiKey) => {
    if (k.status === 'REVOKED') {
      return (
        <span className="chip chip-cancelled" style={{ fontSize: 11, padding: '1px 7px' }}>
          Revoked
        </span>
      );
    }
    if (k.rotatedToId) {
      return (
        <span
          className="chip chip-retrying"
          title="Rotated key in grace period; will expire soon"
          style={{ fontSize: 11, padding: '1px 7px' }}
        >
          Rotating
        </span>
      );
    }
    return (
      <span className="chip chip-delivered" style={{ fontSize: 11, padding: '1px 7px' }}>
        Active
      </span>
    );
  };

  // Keys in rotation grace period do not consume a permanent active slot
  const activeCount = keys.filter((k) => k.status === 'ACTIVE' && !k.rotatedToId).length;

  if (user && !isOwner) {
    return (
      <div className="page-container" style={{ maxWidth: 580, margin: '60px auto', textAlign: 'center' }}>
        <div className="panel" style={{ padding: 48 }}>
          <div style={{ width: 44, height: 44, borderRadius: 'var(--radius-control)', background: 'var(--color-st-retrying-bg)', color: 'var(--color-st-retrying)', display: 'grid', placeItems: 'center', margin: '0 auto 16px' }}>
            <Shield size={24} />
          </div>
          <h2 style={{ fontSize: 18, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 8, letterSpacing: '-0.01em' }}>
            Tenant Owner Access Required
          </h2>
          <p style={{ color: 'var(--color-ink-2)', fontSize: 13, lineHeight: 1.6, marginBottom: 20 }}>
            Configuring API keys requires the <code className="mono">ROLE_TENANT_OWNER</code> authority. Your current account does not have administrative ownership permissions for this workspace.
          </p>
          <a href="/app" className="btn btn-primary" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
            Return to Dashboard
          </a>
        </div>
      </div>
    );
  }

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24, flexWrap: 'wrap', gap: 16 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', margin: '0 0 6px', color: 'var(--color-harbor)' }}>
            API Keys
          </h1>
          <p style={{ color: 'var(--color-ink-2)', fontSize: 13, margin: 0 }}>
            Manage scoped authentication tokens for server-to-server notifications and programmatic API access.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div
            style={{
              fontSize: 12,
              padding: '6px 12px',
              backgroundColor: 'var(--color-chalk)',
              border: '1px solid var(--color-rail)',
              borderRadius: 'var(--radius-control)',
              color: 'var(--color-ink-2)',
            }}
          >
            <span className="mono" style={{ fontWeight: 700, color: activeCount >= 10 ? 'var(--color-st-failed)' : 'var(--color-harbor)' }}>
              {activeCount}
            </span>
            {' / 10 active keys'}
          </div>

          <button
            onClick={() => setShowCreateModal(true)}
            disabled={isSuspended || activeCount >= 10}
            className="btn btn-primary"
            style={{ fontSize: 12 }}
          >
            <Plus size={13} /> Create API Key
          </button>
        </div>
      </div>

      {/* Action Error Banner */}
      {actionError && (
        <div
          style={{
            background: 'var(--color-st-failed-bg)',
            border: '1px solid var(--color-st-failed)',
            borderRadius: 'var(--radius-control)',
            padding: '10px 14px',
            marginBottom: 20,
            color: 'var(--color-st-failed)',
            fontSize: 12,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <AlertCircle size={15} />
            <span>{actionError}</span>
          </div>
          <button
            type="button"
            onClick={() => setActionError(null)}
            style={{ background: 'none', border: 'none', color: 'var(--color-st-failed)', cursor: 'pointer', padding: 2 }}
          >
            <X size={14} />
          </button>
        </div>
      )}

      {/* Content Panel */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
            <span style={{ fontSize: 13 }}>Loading API keys…</span>
          </div>
        ) : error ? (
          <div style={{ padding: 36, textAlign: 'center', color: 'var(--color-st-failed)' }}>
            <p style={{ margin: '0 0 12px', fontSize: 13 }}>{error}</p>
            <button
              onClick={fetchKeys}
              className="btn btn-secondary"
              style={{ fontSize: 12 }}
            >
              Retry
            </button>
          </div>
        ) : keys.length === 0 ? (
          <div style={{ padding: 56, textAlign: 'center' }}>
            <div style={{ width: 40, height: 40, borderRadius: 'var(--radius-control)', background: 'var(--color-chalk)', color: 'var(--color-ink-2)', display: 'grid', placeItems: 'center', margin: '0 auto 14px' }}>
              <Key size={20} />
            </div>
            <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>No API keys generated yet</div>
            <p style={{ color: 'var(--color-ink-2)', fontSize: 13, marginBottom: 18, maxWidth: 420, margin: '0 auto 18px' }}>
              Create an API key to securely authenticate backend microservices and dispatch notifications.
            </p>
            {!isSuspended && (
              <button
                onClick={() => setShowCreateModal(true)}
                className="btn btn-primary"
                style={{ fontSize: 12 }}
              >
                <Plus size={13} /> Create First Key
              </button>
            )}
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table className="table-dense" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ background: 'var(--color-chalk)', borderBottom: '1px solid var(--color-rail)', color: 'var(--color-ink-2)' }}>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Name</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Prefix</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Scopes</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Created</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Expires</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600 }}>Last Used</th>
                  <th style={{ padding: '10px 14px', fontWeight: 600, textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {keys.map((k) => (
                  <tr
                    key={k.id}
                    style={{
                      borderBottom: '1px solid var(--color-rail)',
                      opacity: k.status === 'REVOKED' ? 0.55 : 1,
                    }}
                  >
                    <td style={{ padding: '12px 14px', fontWeight: 600, color: 'var(--color-harbor)' }}>
                      {k.name}
                    </td>
                    <td style={{ padding: '12px 14px' }}>
                      <code className="mono" style={{ background: 'var(--color-chalk)', padding: '2px 6px', borderRadius: 4, fontSize: 11, border: '1px solid var(--color-rail)' }}>
                        {k.keyPrefix}…
                      </code>
                    </td>
                    <td style={{ padding: '12px 14px' }}>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                        {k.scopes.slice(0, 2).map((s) => (
                          <span
                            key={s}
                            style={{
                              background: 'var(--color-chalk)',
                              border: '1px solid var(--color-rail)',
                              borderRadius: 'var(--radius-chip)',
                              padding: '1px 6px',
                              fontSize: 10,
                              fontWeight: 500,
                              color: 'var(--color-harbor)',
                            }}
                          >
                            {s}
                          </span>
                        ))}
                        {k.scopes.length > 2 && (
                          <span
                            title={k.scopes.slice(2).join(', ')}
                            style={{
                              background: 'var(--color-chalk)',
                              borderRadius: 'var(--radius-chip)',
                              padding: '1px 5px',
                              fontSize: 10,
                              color: 'var(--color-ink-2)',
                              fontWeight: 600,
                            }}
                          >
                            +{k.scopes.length - 2}
                          </span>
                        )}
                      </div>
                    </td>
                    <td style={{ padding: '12px 14px' }}>
                      {renderStatusBadge(k)}
                    </td>
                    <td style={{ padding: '12px 14px', color: 'var(--color-ink-2)', fontSize: 11 }}>
                      <span className="mono" style={{ fontSize: 11 }}>
                        {new Date(k.createdAt).toLocaleDateString()}
                      </span>
                    </td>
                    <td style={{ padding: '12px 14px', color: 'var(--color-ink-2)', fontSize: 11 }}>
                      <span className="mono" style={{ fontSize: 11 }}>
                        {formatExpiry(k.expiresAt)}
                      </span>
                    </td>
                    <td style={{ padding: '12px 14px', color: 'var(--color-ink-2)', fontSize: 11 }}>
                      {k.lastUsedAt ? (
                        <span className="mono" style={{ fontSize: 11 }}>
                          {new Date(k.lastUsedAt).toLocaleDateString()}
                        </span>
                      ) : (
                        <span style={{ fontStyle: 'italic', color: 'var(--color-ink-2)' }}>Never</span>
                      )}
                    </td>
                    <td style={{ padding: '12px 14px', textAlign: 'right' }}>
                      {k.status === 'ACTIVE' ? (
                        <div style={{ display: 'inline-flex', gap: 6 }}>
                          <button
                            type="button"
                            onClick={() => {
                              setGracePeriodHours(24);
                              setKeyToRotate(k);
                            }}
                            disabled={isSuspended || !!k.rotatedToId}
                            title={k.rotatedToId ? 'Key already rotated (in grace period)' : undefined}
                            className="btn btn-secondary"
                            style={{
                              padding: '4px 8px',
                              fontSize: 11,
                              opacity: k.rotatedToId ? 0.5 : 1,
                            }}
                          >
                            <RefreshCw size={11} /> Rotate
                          </button>
                          <button
                            type="button"
                            onClick={() => setKeyToRevoke(k)}
                            disabled={isSuspended}
                            className="btn btn-secondary"
                            style={{
                              padding: '4px 8px',
                              fontSize: 11,
                              color: 'var(--color-st-failed)',
                            }}
                          >
                            <Trash2 size={11} /> Revoke
                          </button>
                        </div>
                      ) : (
                        <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>Revoked</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Quick-start & Integration Guide */}
      <div className="panel" style={{ marginTop: 24, padding: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <Terminal size={15} style={{ color: 'var(--color-signal)' }} />
          <h2 style={{ fontSize: 14, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
            Integration & Programmatic Authentication
          </h2>
        </div>
        <p style={{ color: 'var(--color-ink-2)', fontSize: 12, margin: '0 0 12px', lineHeight: 1.5 }}>
          Authenticate server-to-server requests using the <code className="mono">Authorization: Bearer ntf_...</code> scheme or <code className="mono">X-API-Key: ntf_...</code> request header.
        </p>
        <div style={{ background: '#0F172A', padding: '14px 16px', borderRadius: 'var(--radius-control)', overflowX: 'auto', marginBottom: 12 }}>
          <pre className="mono" style={{ margin: 0, color: '#E2E8F0', fontSize: 11, lineHeight: 1.6 }}>
{`curl -X POST http://localhost:8080/api/v1/notifications \\
  -H "Authorization: Bearer ntf_your_api_key_here" \\
  -H "Content-Type: application/json" \\
  -d '{
    "recipient": "user@example.com",
    "channel": "EMAIL",
    "subject": "System Alert",
    "body": "Notification body content"
  }'`}
          </pre>
        </div>
        <div style={{ fontSize: 12, color: 'var(--color-ink-2)', display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontWeight: 600, color: 'var(--color-st-failed)', fontSize: 11 }}>403 INSUFFICIENT_SCOPE:</span>
          <span>Returned when an API key lacks the specific scope (e.g. <code className="mono">notifications:send</code>) required by the target resource.</span>
        </div>
      </div>

      {/* CREATE KEY MODAL */}
      {showCreateModal && (
        <div className="modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 520 }}>
            <div className="modal-header">
              <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Generate Scoped API Key
              </h2>
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                <X size={15} />
              </button>
            </div>

            {formError && (
              <div
                style={{
                  margin: '12px 20px 0',
                  padding: '8px 12px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  border: '1px solid var(--color-st-failed)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                }}
              >
                {formError}
              </div>
            )}

            <form onSubmit={handleCreate}>
              <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>
                    Key Name / Service Identifier
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. Production Dispatcher or Billing Webhook Worker"
                    value={keyName}
                    onChange={(e) => setKeyName(e.target.value)}
                    required
                    className="input-text"
                  />
                </div>

                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                    <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                      Scopes & Access Controls
                    </label>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button
                        type="button"
                        onClick={() => setSelectedScopes(['notifications:send'])}
                        className="btn btn-secondary"
                        style={{ padding: '2px 8px', fontSize: 10 }}
                      >
                        Send Only
                      </button>
                      <button
                        type="button"
                        onClick={() => setSelectedScopes(AVAILABLE_SCOPES.map((s) => s.value))}
                        className="btn btn-secondary"
                        style={{ padding: '2px 8px', fontSize: 10 }}
                      >
                        Full Access
                      </button>
                    </div>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 180, overflowY: 'auto', padding: '2px' }}>
                    {AVAILABLE_SCOPES.map((scope) => {
                      const checked = selectedScopes.includes(scope.value);
                      return (
                        <label
                          key={scope.value}
                          style={{
                            display: 'flex',
                            alignItems: 'flex-start',
                            gap: 10,
                            fontSize: 12,
                            cursor: 'pointer',
                            padding: '8px 10px',
                            borderRadius: 'var(--radius-control)',
                            border: checked ? '1px solid var(--color-signal)' : '1px solid var(--color-rail)',
                            background: checked ? 'var(--color-st-processing-bg)' : 'transparent',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={(e) => {
                              if (e.target.checked) {
                                setSelectedScopes([...selectedScopes, scope.value]);
                              } else {
                                setSelectedScopes(selectedScopes.filter((s) => s !== scope.value));
                              }
                            }}
                            style={{ marginTop: 2, cursor: 'pointer' }}
                          />
                          <div>
                            <div style={{ fontWeight: 600, color: 'var(--color-harbor)' }}>
                              {scope.label} <code className="mono" style={{ fontSize: 10, color: 'var(--color-ink-2)' }}>({scope.value})</code>
                            </div>
                            <div style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 2 }}>{scope.desc}</div>
                          </div>
                        </label>
                      );
                    })}
                  </div>
                </div>

                <div>
                  <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>
                    Expiration (Optional)
                  </label>
                  <input
                    type="datetime-local"
                    value={expiresAt}
                    onChange={(e) => setExpiresAt(e.target.value)}
                    className="input-text"
                  />
                  <span style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'block' }}>
                    Leave empty for no expiry (max 1 year).
                  </span>
                </div>
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="btn btn-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="btn btn-primary"
                >
                  {submitting ? 'Generating…' : 'Generate Key'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ONE-TIME REVEAL MODAL */}
      {showRevealModal && revealedKey && (
        <OneTimeSecretModal
          title="API Key Generated Successfully"
          secret={revealedKey.apiKey}
          keyName={revealedKey.name}
          warningText="Copy this key now. For security purposes, it will never be displayed again."
          onClose={() => {
            setShowRevealModal(false);
            setRevealedKey(null);
          }}
        />
      )}

      {/* ROTATE CONFIRM MODAL */}
      {keyToRotate && (
        <div className="modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 480 }}>
            <div className="modal-header">
              <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Rotate API Key
              </h2>
              <button
                type="button"
                onClick={() => setKeyToRotate(null)}
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                <X size={15} />
              </button>
            </div>

            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <p style={{ color: 'var(--color-ink-2)', fontSize: 12, lineHeight: 1.5, margin: 0 }}>
                Rotating <strong>{keyToRotate.name}</strong> (<code className="mono">{keyToRotate.keyPrefix}…</code>) will generate a new replacement key inheriting identical scopes.
              </p>

              <div>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', display: 'block', marginBottom: 8 }}>
                  Overlap Grace Period
                </label>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8 }}>
                  {[
                    { value: 0, label: 'Immediate' },
                    { value: 1, label: '1 hour' },
                    { value: 24, label: '24 hours' },
                    { value: 168, label: '7 days' },
                  ].map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setGracePeriodHours(opt.value)}
                      style={{
                        padding: '8px 4px',
                        borderRadius: 'var(--radius-control)',
                        border: gracePeriodHours === opt.value
                          ? '1.5px solid var(--color-signal)'
                          : '1px solid var(--color-rail)',
                        background: gracePeriodHours === opt.value
                          ? 'var(--color-st-processing-bg)'
                          : 'var(--color-paper)',
                        color: gracePeriodHours === opt.value
                          ? 'var(--color-signal)'
                          : 'var(--color-harbor)',
                        fontWeight: gracePeriodHours === opt.value ? 700 : 500,
                        fontSize: 12,
                        cursor: 'pointer',
                        textAlign: 'center',
                        transition: 'all 0.15s ease',
                      }}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
                <p style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 8, margin: '8px 0 0', lineHeight: 1.4 }}>
                  {gracePeriodHours === 0
                    ? 'The existing key will be revoked immediately.'
                    : `The existing key will remain active for ${gracePeriodHours === 1 ? '1 hour' : gracePeriodHours === 24 ? '24 hours' : '7 days'} (without extending its original expiry) to avoid downtime during service redeployments.`}
                </p>
              </div>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setKeyToRotate(null)}
                className="btn btn-secondary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleRotateConfirm}
                disabled={rotating}
                className="btn btn-primary"
              >
                {rotating ? 'Rotating…' : 'Rotate Key'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* REVOKE CONFIRM MODAL */}
      {keyToRevoke && (
        <div className="modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 440 }}>
            <div className="modal-header">
              <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-st-failed)' }}>
                Revoke API Key
              </h2>
              <button
                type="button"
                onClick={() => setKeyToRevoke(null)}
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                <X size={15} />
              </button>
            </div>

            <div className="modal-body">
              <p style={{ color: 'var(--color-ink-2)', fontSize: 13, lineHeight: 1.5, margin: 0 }}>
                Are you sure you want to permanently revoke <strong>{keyToRevoke.name}</strong> (<code className="mono">{keyToRevoke.keyPrefix}…</code>)?
                Any external service or daemon using this key will immediately receive 401 Unauthorized errors.
              </p>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setKeyToRevoke(null)}
                className="btn btn-secondary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleRevokeConfirm}
                disabled={revoking}
                className="btn btn-danger"
              >
                {revoking ? 'Revoking…' : 'Yes, Revoke Key'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}