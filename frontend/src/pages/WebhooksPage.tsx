import React, { useState, useEffect } from 'react';
import { useAuth } from '../auth/AuthContext';
import {
  getWebhookEndpoint,
  updateWebhookEndpoint,
  testWebhookEndpoint,
  rotateWebhookSecret,
  deleteWebhookEndpoint,
  type WebhookEndpoint,
  type WebhookTestResult,
} from '../api/webhooks';
import OneTimeSecretModal from '../components/OneTimeSecretModal';
import {
  Webhook,
  Send,
  RotateCw,
  CheckCircle2,
  AlertTriangle,
  Code2,
  ChevronDown,
  ChevronUp,
  Shield,
  Radio,
  X,
  AlertCircle,
  Trash2,
} from 'lucide-react';

export default function WebhooksPage() {
  const { user } = useAuth();
  const isSuspended = user?.tenantStatus === 'SUSPENDED';
  const isOwner = Boolean(
    (user as any)?.role === 'TENANT_OWNER' ||
    (user as any)?.role === 'ROLE_TENANT_OWNER' ||
    user?.roles?.some((r) => r === 'TENANT_OWNER' || r === 'ROLE_TENANT_OWNER')
  );

  const [endpoint, setEndpoint] = useState<WebhookEndpoint | null>(null);
  const [url, setUrl] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Test Ping State
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<WebhookTestResult | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  // Secret Modal State
  const [revealedSecret, setRevealedSecret] = useState<string | null>(null);
  const [showRotateConfirm, setShowRotateConfirm] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [rotateError, setRotateError] = useState<string | null>(null);

  // Delete Endpoint Modal State
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleteSuccess, setDeleteSuccess] = useState(false);

  // Collapsible Guide State
  const [guideOpen, setGuideOpen] = useState(false);
  const [activeLang, setActiveLang] = useState<'node' | 'python' | 'go'>('node');

  const fetchEndpoint = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getWebhookEndpoint();
      setEndpoint(data);
      if (data) {
        setUrl(data.url);
        setEnabled(data.enabled);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load webhook configuration');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (user && !isOwner) {
      setLoading(false);
      return;
    }
    fetchEndpoint();
  }, [user, isOwner]);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedUrl = url.trim();
    if (!trimmedUrl) {
      setError('Destination URL is required');
      return;
    }

    try {
      const parsed = new URL(trimmedUrl);
      if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
        setError('Destination URL must use http:// or https://');
        return;
      }
      if (parsed.protocol === 'http:' && parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
        setError('Insecure HTTP endpoints are not permitted in production. Please use an HTTPS URL.');
        return;
      }
    } catch {
      setError('Please provide a valid, well-formed URL (e.g. https://api.yourdomain.com/webhooks)');
      return;
    }

    try {
      setSaving(true);
      setError(null);
      setSaveSuccess(false);

      const result = await updateWebhookEndpoint({
        url: trimmedUrl,
        enabled,
      });

      setEndpoint(result);
      setSaveSuccess(true);
      if (result.signingSecret) {
        setRevealedSecret(result.signingSecret);
      }
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch (err: any) {
      setError(err.message || 'Failed to save webhook configuration');
    } finally {
      setSaving(false);
    }
  };

  const handleTestPing = async () => {
    try {
      setTesting(true);
      setTestResult(null);
      setTestError(null);
      const res = await testWebhookEndpoint();
      setTestResult(res);
    } catch (err: any) {
      setTestError(err.message || 'Failed to dispatch test ping');
    } finally {
      setTesting(false);
    }
  };

  const handleRotateSecretSubmit = async () => {
    try {
      setRotating(true);
      setRotateError(null);
      const result = await rotateWebhookSecret();
      setShowRotateConfirm(false);
      if (result.signingSecret) {
        setRevealedSecret(result.signingSecret);
      }
      await fetchEndpoint();
    } catch (err: any) {
      setRotateError(err.message || 'Failed to rotate secret');
    } finally {
      setRotating(false);
    }
  };

  const handleDeleteEndpoint = async () => {
    try {
      setDeleting(true);
      setDeleteError(null);
      await deleteWebhookEndpoint();
      setShowDeleteConfirm(false);
      setEndpoint(null);
      setUrl('');
      setEnabled(true);
      setTestResult(null);
      setDeleteSuccess(true);
      setTimeout(() => setDeleteSuccess(false), 4000);
    } catch (err: any) {
      setDeleteError(err.message || 'Failed to delete webhook endpoint from database');
    } finally {
      setDeleting(false);
    }
  };

  const codeSnippets = {
    node: `// Node.js (crypto)
const crypto = require('crypto');

function verifyNotifySignature(payload, signatureHeader, secret) {
  const hmac = crypto.createHmac('sha256', secret);
  const digest = 'sha256=' + hmac.update(payload).digest('hex');
  return crypto.timingSafeEqual(Buffer.from(signatureHeader), Buffer.from(digest));
}`,
    python: `# Python (hmac + hashlib)
import hmac, hashlib

def verify_notify_signature(payload_bytes, signature_header, secret_bytes):
    expected = "sha256=" + hmac.new(secret_bytes, payload_bytes, hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature_header, expected)`,
    go: `// Go (crypto/hmac)
package main

import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/hex"
)

func VerifySignature(payload []byte, headerSig string, secret []byte) bool {
    mac := hmac.New(sha256.New, secret)
    mac.Write(payload)
    expected := "sha256=" + hex.EncodeToString(mac.Sum(nil))
    return hmac.Equal([]byte(headerSig), []byte(expected))
}`,
  };

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
            Configuring webhook destination endpoints and rotating HMAC signing secrets requires the <code className="mono">ROLE_TENANT_OWNER</code> authority. Your current account does not have administrative ownership permissions for this workspace.
          </p>
          <a href="/app" className="btn btn-primary" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
            Return to Dashboard
          </a>
        </div>
      </div>
    );
  }

  return (
    <div className="page-container" style={{ maxWidth: 1000 }}>
      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)', margin: '0 0 6px' }}>
          Webhooks Configuration
        </h1>
        <p style={{ color: 'var(--color-ink-2)', fontSize: 13, margin: 0 }}>
          Manage outbound event dispatch destinations, inspect delivery ping outcomes, and rotate cryptographic HMAC signing secrets.
        </p>
      </div>

      {/* Secret One-Time Modal */}
      {revealedSecret && (
        <OneTimeSecretModal
          title="Webhook Signing Secret Generated"
          secret={revealedSecret}
          warningText="Copy this signing secret now. It will never be displayed again. Outbound webhook signatures will be computed using this secret."
          onClose={() => setRevealedSecret(null)}
        />
      )}

      {/* Endpoint Configuration Panel */}
      <div className="panel" style={{ padding: 22, marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ width: 28, height: 28, borderRadius: 'var(--radius-control)', background: 'var(--color-st-processing-bg)', color: 'var(--color-signal)', display: 'grid', placeItems: 'center' }}>
              <Webhook size={15} />
            </div>
            <div>
              <h2 style={{ fontSize: 14, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
                Destination Endpoint
              </h2>
            </div>
          </div>

          {endpoint?.isDemo && (
            <span
              className="chip chip-scheduled"
              style={{ fontSize: 11, padding: '2px 8px' }}
            >
              <Radio size={11} style={{ marginRight: 4 }} />
              Demo Receiver
            </span>
          )}
        </div>

        {error && (
          <div
            style={{
              padding: '9px 13px',
              backgroundColor: 'var(--color-st-failed-bg)',
              border: '1px solid var(--color-st-failed)',
              borderRadius: 'var(--radius-control)',
              color: 'var(--color-st-failed)',
              fontSize: 12,
              marginBottom: 16,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <AlertCircle size={14} />
            <span>{error}</span>
          </div>
        )}

        {saveSuccess && (
          <div
            style={{
              padding: '9px 13px',
              backgroundColor: 'var(--color-st-delivered-bg)',
              border: '1px solid var(--color-st-delivered)',
              borderRadius: 'var(--radius-control)',
              color: 'var(--color-st-delivered)',
              fontSize: 12,
              marginBottom: 16,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <CheckCircle2 size={14} />
            <span>Webhook endpoint configuration saved successfully.</span>
          </div>
        )}

        {deleteSuccess && (
          <div
            style={{
              padding: '9px 13px',
              backgroundColor: 'var(--color-st-delivered-bg)',
              border: '1px solid var(--color-st-delivered)',
              borderRadius: 'var(--radius-control)',
              color: 'var(--color-st-delivered)',
              fontSize: 12,
              marginBottom: 16,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <CheckCircle2 size={14} />
            <span>Webhook endpoint was permanently deleted from the database.</span>
          </div>
        )}

        {loading ? (
          <div style={{ padding: '40px 0', textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 10px' }} />
            <span style={{ fontSize: 13 }}>Loading webhook configuration…</span>
          </div>
        ) : (
          <div>
            {endpoint && (
              <div
                style={{
                  padding: '12px 16px',
                  backgroundColor: 'var(--color-paper)',
                  border: '1px solid var(--color-rail)',
                  borderRadius: 'var(--radius-control)',
                  marginBottom: 18,
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6, flexWrap: 'wrap', gap: 6 }}>
                  <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                    Saved Database Destination:
                  </span>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <span
                      className={endpoint.enabled ? 'chip chip-delivered' : 'chip chip-failed'}
                      style={{ fontSize: 11, padding: '2px 8px' }}
                    >
                      {endpoint.enabled ? 'Active / Enabled' : 'Disabled'}
                    </span>
                    {endpoint.isDemo && (
                      <span className="chip chip-scheduled" style={{ fontSize: 11, padding: '2px 8px' }}>
                        Demo Mode
                      </span>
                    )}
                  </div>
                </div>
                <div className="mono" style={{ fontSize: 12, wordBreak: 'break-all', color: 'var(--color-signal)' }}>
                  {endpoint.url}
                </div>
                <div style={{ fontSize: 11, color: 'var(--color-ink-3)', marginTop: 6, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                  <span>Endpoint ID: <code className="mono">{endpoint.id}</code></span>
                  <span>Updated: {new Date(endpoint.updatedAt).toLocaleString()}</span>
                </div>
              </div>
            )}

            <form onSubmit={handleSave}>
              <div style={{ marginBottom: 16 }}>
                <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>
                  {endpoint ? 'Update Destination URL:' : 'Payload Destination URL:'}
                </label>
                <input
                  type="url"
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="https://api.yourdomain.com/webhooks/notify"
                  className="input-text mono"
                  style={{ fontSize: 12 }}
                />
                <span style={{ fontSize: 11, color: 'var(--color-ink-2)', marginTop: 4, display: 'block' }}>
                  Must be an accessible HTTPS endpoint. Non-routable internal IP ranges and link-local addresses are rejected by SSRF egress filters.
                </span>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 20 }}>
                <input
                  type="checkbox"
                  id="webhook-enabled"
                  checked={enabled}
                  onChange={(e) => setEnabled(e.target.checked)}
                  style={{ cursor: 'pointer' }}
                />
                <label htmlFor="webhook-enabled" style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', cursor: 'pointer' }}>
                  Enable outbound event deliveries to this endpoint
                </label>
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  <button
                    type="button"
                    onClick={handleTestPing}
                    disabled={testing || !endpoint || isSuspended}
                    className="btn btn-secondary"
                    style={{ fontSize: 12 }}
                  >
                    <Send size={12} />
                    {testing ? 'Sending Ping…' : 'Send Test Ping'}
                  </button>

                  {endpoint && (
                    <button
                      type="button"
                      onClick={() => {
                        setDeleteError(null);
                        setShowDeleteConfirm(true);
                      }}
                      disabled={deleting || isSuspended}
                      className="btn btn-secondary"
                      style={{ fontSize: 12, color: 'var(--color-st-failed)', borderColor: 'rgba(239, 68, 68, 0.3)' }}
                      title="Permanently delete endpoint from database"
                    >
                      <Trash2 size={12} />
                      Delete Endpoint
                    </button>
                  )}
                </div>

                <button
                  type="submit"
                  disabled={saving || isSuspended}
                  className="btn btn-primary"
                  style={{ fontSize: 12 }}
                >
                  {saving ? 'Saving…' : 'Save Endpoint'}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Test Ping Inline Result Card */}
        {testResult && (
          <div
            style={{
              marginTop: 18,
              padding: 14,
              borderRadius: 'var(--radius-control)',
              backgroundColor: testResult.delivered ? 'var(--color-st-delivered-bg)' : 'var(--color-st-failed-bg)',
              border: `1px solid ${testResult.delivered ? 'var(--color-st-delivered)' : 'var(--color-st-failed)'}`,
              fontSize: 12,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 600, marginBottom: 4 }}>
              {testResult.delivered ? (
                <CheckCircle2 size={15} style={{ color: 'var(--color-st-delivered)' }} />
              ) : (
                <AlertTriangle size={15} style={{ color: 'var(--color-st-failed)' }} />
              )}
              <span style={{ color: 'var(--color-harbor)' }}>
                {testResult.delivered ? 'Test Delivery Succeeded' : 'Test Delivery Failed'}
              </span>
              <span className="mono" style={{ marginLeft: 'auto', fontWeight: 700, color: testResult.delivered ? 'var(--color-st-delivered)' : 'var(--color-st-failed)' }}>
                HTTP {testResult.httpStatus ?? '—'}
              </span>
            </div>
            <div style={{ color: 'var(--color-ink-2)', fontSize: 11, marginTop: 4 }}>
              Outcome: <strong style={{ color: 'var(--color-harbor)' }}>{testResult.outcome}</strong> {testResult.detail ? `— ${testResult.detail}` : ''}
            </div>
          </div>
        )}

        {testError && (
          <div
            style={{
              marginTop: 16,
              padding: 12,
              backgroundColor: 'var(--color-st-failed-bg)',
              border: '1px solid var(--color-st-failed)',
              color: 'var(--color-st-failed)',
              fontSize: 12,
              borderRadius: 'var(--radius-control)',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <AlertCircle size={14} />
            <span>{testError}</span>
          </div>
        )}
      </div>

      {/* Signing Secret Panel */}
      <div className="panel" style={{ padding: 22, marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', gap: 12 }}>
          <div>
            <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)', margin: '0 0 2px', letterSpacing: '-0.01em' }}>
              HMAC Signing Secret
            </h2>
            <span style={{ fontSize: 12, color: 'var(--color-ink-2)' }}>
              Used to sign payload headers (<code className="mono">X-Notify-Signature</code>) for authenticity and integrity verification.
            </span>
          </div>

          <button
            type="button"
            onClick={() => setShowRotateConfirm(true)}
            disabled={!endpoint || isSuspended}
            className="btn btn-secondary"
            style={{ fontSize: 12 }}
          >
            <RotateCw size={12} />
            Rotate Secret
          </button>
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            backgroundColor: 'var(--color-chalk)',
            border: '1px solid var(--color-rail)',
            borderRadius: 'var(--radius-control)',
            padding: '10px 14px',
          }}
        >
          <span className="mono" style={{ fontSize: 13, letterSpacing: '0.12em', color: 'var(--color-ink-2)' }}>
            ••••••••••••••••••••••••••••••••••••••••••••••••
          </span>
          <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--color-ink-2)', fontStyle: 'italic' }}>
            Masked for security
          </span>
        </div>
      </div>

      {/* Signature Verification Guide Drawer */}
      <div className="panel" style={{ padding: 20 }}>
        <button
          type="button"
          onClick={() => setGuideOpen((prev) => !prev)}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            width: '100%',
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            textAlign: 'left',
            padding: 0,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Code2 size={16} style={{ color: 'var(--color-signal)' }} />
            <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--color-harbor)' }}>
              How to Verify Webhook Signatures
            </span>
          </div>
          {guideOpen ? <ChevronUp size={16} style={{ color: 'var(--color-ink-2)' }} /> : <ChevronDown size={16} style={{ color: 'var(--color-ink-2)' }} />}
        </button>

        {guideOpen && (
          <div style={{ marginTop: 16, borderTop: '1px solid var(--color-rail)', paddingTop: 14 }}>
            <p style={{ fontSize: 12, color: 'var(--color-ink-2)', lineHeight: 1.6, margin: '0 0 12px' }}>
              Every webhook delivery includes an <code className="mono">X-Notify-Signature</code> header containing{' '}
              <code className="mono">sha256=hash</code> computed over raw request body bytes using your signing secret.
            </p>

            {/* Language Tabs */}
            <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
              {(['node', 'python', 'go'] as const).map((lang) => (
                <button
                  key={lang}
                  type="button"
                  onClick={() => setActiveLang(lang)}
                  style={{
                    padding: '4px 10px',
                    fontSize: 11,
                    fontWeight: activeLang === lang ? 700 : 500,
                    backgroundColor: activeLang === lang ? 'var(--color-harbor)' : 'var(--color-chalk)',
                    color: activeLang === lang ? '#FFFFFF' : 'var(--color-ink-2)',
                    border: '1px solid var(--color-rail)',
                    borderRadius: 'var(--radius-control)',
                    cursor: 'pointer',
                    transition: 'all 0.15s ease',
                  }}
                >
                  {lang === 'node' ? 'Node.js' : lang === 'python' ? 'Python' : 'Go'}
                </button>
              ))}
            </div>

            <pre
              className="mono"
              style={{
                padding: 14,
                backgroundColor: '#0F172A',
                color: '#E2E8F0',
                borderRadius: 'var(--radius-control)',
                fontSize: 11,
                overflowX: 'auto',
                lineHeight: 1.6,
                margin: 0,
              }}
            >
              {codeSnippets[activeLang]}
            </pre>
          </div>
        )}
      </div>

      {/* Rotate Confirmation Modal */}
      {showRotateConfirm && (
        <div className="modal-overlay modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 440 }}>
            <div className="modal-header">
              <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Rotate Webhook Secret?
              </h2>
              <button
                type="button"
                onClick={() => setShowRotateConfirm(false)}
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                <X size={15} />
              </button>
            </div>

            <div className="modal-body">
              <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.5, margin: 0 }}>
                Rotating the secret invalidates the existing secret immediately. Any webhook payload received before your receiver is updated with the replacement secret will fail cryptographic verification.
              </p>
              {rotateError && (
                <div
                  style={{
                    marginTop: 12,
                    padding: '8px 12px',
                    backgroundColor: 'var(--color-st-failed-bg)',
                    border: '1px solid var(--color-st-failed)',
                    borderRadius: 'var(--radius-control)',
                    color: 'var(--color-st-failed)',
                    fontSize: 12,
                  }}
                >
                  {rotateError}
                </div>
              )}
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setShowRotateConfirm(false)}
                className="btn btn-secondary"
              >
                Keep Current
              </button>
              <button
                type="button"
                onClick={handleRotateSecretSubmit}
                disabled={rotating}
                className="btn btn-danger"
              >
                {rotating ? 'Rotating…' : 'Rotate Secret'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {showDeleteConfirm && (
        <div className="modal-overlay modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 460 }}>
            <div className="modal-header">
              <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-st-failed)' }}>
                Delete Webhook Endpoint Permanently?
              </h2>
              <button
                type="button"
                onClick={() => setShowDeleteConfirm(false)}
                className="btn btn-ghost"
                style={{ padding: 4 }}
              >
                <X size={15} />
              </button>
            </div>

            <div className="modal-body">
              <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.5, margin: 0 }}>
                This will permanently delete your webhook endpoint configuration (<code className="mono">{endpoint?.url}</code>) and its encrypted HMAC signing secret from the Notify database.
              </p>
              <p style={{ fontSize: 12, color: 'var(--color-st-retrying)', marginTop: 10, lineHeight: 1.5 }}>
                ⚠️ Any outbound webhook dispatches will not be delivered until a new destination endpoint is configured.
              </p>
              {deleteError && (
                <div
                  style={{
                    marginTop: 12,
                    padding: '8px 12px',
                    backgroundColor: 'var(--color-st-failed-bg)',
                    border: '1px solid var(--color-st-failed)',
                    borderRadius: 'var(--radius-control)',
                    color: 'var(--color-st-failed)',
                    fontSize: 12,
                  }}
                >
                  {deleteError}
                </div>
              )}
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setShowDeleteConfirm(false)}
                className="btn btn-secondary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDeleteEndpoint}
                disabled={deleting}
                className="btn btn-danger"
              >
                {deleting ? 'Deleting from DB…' : 'Delete Permanently'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}