import React, { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  createNotification,
  type Channel,
  type CreateNotificationRequest,
} from '../api/notifications';
import {
  listTemplates,
  getTemplate,
  type Template,
  type TemplateDetail,
  type TemplateVersion,
} from '../api/templates';
import { extractTemplateVariables, interpolateTemplateVariables } from '../components/templateVariableUtils';
import SandboxedIframe from '../components/SandboxedIframe';
import {
  ArrowLeft,
  Mail,
  Webhook,
  Bell,
  Sparkles,
  AlertTriangle,
} from 'lucide-react';

export default function CreateNotificationPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const isSuspended = user?.tenantStatus === 'SUSPENDED';

  // Step 1: Channel
  const [channel, setChannel] = useState<Channel>('EMAIL');

  // Step 2: Recipient
  const [recipient, setRecipient] = useState('');
  const [recipientError, setRecipientError] = useState<string | null>(null);

  // Step 3: Content Mode
  const [contentMode, setContentMode] = useState<'custom' | 'template'>('custom');

  // Custom Content
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');

  // Template Selection
  const [templates, setTemplates] = useState<Template[]>([]);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string>('');
  const [templateDetail, setTemplateDetail] = useState<TemplateDetail | null>(null);
  const [selectedVersionNumber, setSelectedVersionNumber] = useState<number | ''>('');
  const [activeVersion, setActiveVersion] = useState<TemplateVersion | null>(null);
  const [templateVariables, setTemplateVariables] = useState<Record<string, string>>({});

  // Step 4: Dispatch Mode
  const [dispatchMode, setDispatchMode] = useState<'immediate' | 'schedule'>('immediate');
  const [scheduledAt, setScheduledAt] = useState('');
  const [scheduleError, setScheduleError] = useState<string | null>(null);

  // Step 5: Idempotency Key
  const [idempotencyKey, setIdempotencyKey] = useState('');

  // Step 6: Metadata JSON
  const [metadataExpanded, setMetadataExpanded] = useState(false);
  const [metadataJson, setMetadataJson] = useState('');
  const [metadataError, setMetadataError] = useState<string | null>(null);

  // Submission State
  const [submitting, setSubmitting] = useState(false);
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [rateLimitCountdown, setRateLimitCountdown] = useState<number | null>(null);
  const [replayBanner, setReplayBanner] = useState<{ id: string } | null>(null);

  // Load templates on mount or channel change
  useEffect(() => {
    let mounted = true;
    listTemplates()
      .then((data) => {
        if (mounted) {
          // Filter templates matching current channel
          const matched = data.filter((t) => t.channel === channel);
          setTemplates(matched);
        }
      })
      .catch(() => {
        if (mounted) setTemplates([]);
      });

    return () => {
      mounted = false;
    };
  }, [channel]);

  // Load full template detail when a template is selected
  useEffect(() => {
    if (!selectedTemplateId) {
      setTemplateDetail(null);
      setActiveVersion(null);
      return;
    }

    let mounted = true;
    getTemplate(selectedTemplateId)
      .then((detail) => {
        if (!mounted) return;
        setTemplateDetail(detail);
        // Find latest published or first available version
        const published = detail.versions.find((v) => v.status === 'PUBLISHED');
        const defaultVer = published || detail.versions[0];
        if (defaultVer) {
          setSelectedVersionNumber(defaultVer.versionNumber);
          setActiveVersion(defaultVer);
          // Initialize template variables
          const vars = defaultVer.variables || extractTemplateVariables(defaultVer.subject, defaultVer.body);
          const initialMap: Record<string, string> = {};
          for (const v of vars) {
            initialMap[v] = '';
          }
          setTemplateVariables(initialMap);
        }
      })
      .catch(() => {
        if (mounted) {
          setTemplateDetail(null);
          setActiveVersion(null);
        }
      });

    return () => {
      mounted = false;
    };
  }, [selectedTemplateId]);

  // Update active version when version selector changes
  const handleVersionChange = (verNum: number) => {
    setSelectedVersionNumber(verNum);
    if (!templateDetail) return;
    const v = templateDetail.versions.find((item) => item.versionNumber === verNum);
    if (v) {
      setActiveVersion(v);
      const vars = v.variables || extractTemplateVariables(v.subject, v.body);
      const newMap: Record<string, string> = {};
      for (const key of vars) {
        newMap[key] = templateVariables[key] || '';
      }
      setTemplateVariables(newMap);
    }
  };

  const handleGenerateUuid = () => {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      setIdempotencyKey(crypto.randomUUID());
    } else {
      setIdempotencyKey(`ntf_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`);
    }
  };

  const validateRecipient = (): boolean => {
    const rec = recipient.trim();
    if (!rec) {
      setRecipientError('Recipient is required');
      return false;
    }
    if (channel === 'EMAIL') {
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!emailRegex.test(rec)) {
        setRecipientError('Please enter a valid email address (e.g. user@example.com)');
        return false;
      }
    } else if (channel === 'WEBHOOK') {
      try {
        const url = new URL(rec);
        if (url.protocol !== 'https:' && url.protocol !== 'http:') {
          setRecipientError('Destination must be a valid HTTP or HTTPS URL');
          return false;
        }
      } catch {
        setRecipientError('Please enter a valid URL (e.g. https://api.example.com/webhook)');
        return false;
      }
    }
    setRecipientError(null);
    return true;
  };

  const validateSchedule = (): boolean => {
    if (dispatchMode !== 'schedule') return true;
    if (!scheduledAt) {
      setScheduleError('Please pick a scheduled date and time');
      return false;
    }
    const schedDate = new Date(scheduledAt);
    const now = new Date();
    if (isNaN(schedDate.getTime())) {
      setScheduleError('Invalid date format');
      return false;
    }
    if (schedDate.getTime() <= now.getTime()) {
      setScheduleError('Scheduled time must be in the future');
      return false;
    }
    const maxFuture = new Date(now.getTime() + 90 * 24 * 60 * 60 * 1000);
    if (schedDate.getTime() > maxFuture.getTime()) {
      setScheduleError('Scheduled time cannot be more than 90 days in the future');
      return false;
    }
    setScheduleError(null);
    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setGeneralError(null);
    setReplayBanner(null);

    if (isSuspended) {
      setGeneralError('Cannot send notifications: your tenant account is suspended.');
      return;
    }

    if (!validateRecipient() || !validateSchedule()) {
      return;
    }

    if (contentMode === 'custom' && !body.trim()) {
      setGeneralError('Message body cannot be empty');
      return;
    }

    if (contentMode === 'template' && !selectedTemplateId) {
      setGeneralError('Please select a template');
      return;
    }

    let parsedMetadata: Record<string, any> | undefined;
    if (metadataJson.trim()) {
      try {
        parsedMetadata = JSON.parse(metadataJson);
      } catch {
        setMetadataError('Invalid JSON format in metadata attributes');
        return;
      }
    }
    setMetadataError(null);

    try {
      setSubmitting(true);

      const payload: CreateNotificationRequest = {
        channel,
        recipient: recipient.trim(),
        subject: channel === 'EMAIL' ? (contentMode === 'custom' ? subject.trim() || undefined : undefined) : undefined,
        body: contentMode === 'custom' ? body.trim() : undefined,
        templateId: contentMode === 'template' ? selectedTemplateId : undefined,
        templateVersion: contentMode === 'template' && selectedVersionNumber !== '' ? Number(selectedVersionNumber) : undefined,
        templateVariables: contentMode === 'template' ? templateVariables : undefined,
        idempotencyKey: idempotencyKey.trim() || undefined,
        scheduledAt: dispatchMode === 'schedule' && scheduledAt ? new Date(scheduledAt).toISOString() : undefined,
        metadata: parsedMetadata,
      };

      const result = await createNotification(payload, idempotencyKey.trim() || undefined);

      if (result.isReplay) {
        setReplayBanner({ id: result.data.id });
      } else {
        // Navigate to the newly created notification's detail page
        navigate(`/app/notifications/${result.data.id}`);
      }
    } catch (err: any) {
      const msg = err.message || '';
      if (err.status === 429 || msg.includes('429') || msg.includes('RATE_LIMITED')) {
        setRateLimitCountdown(60);
        setGeneralError('Rate limit exceeded (429). Please wait before submitting another notification.');
      } else if (err.status === 409 || msg.includes('409') || msg.toLowerCase().includes('conflict')) {
        setGeneralError(`Idempotency Conflict (409): ${msg}. This idempotency key was previously used with different request parameters.`);
      } else if (err.status === 422 || msg.includes('422') || msg.includes('VALIDATION_FAILED')) {
        setGeneralError(`Validation Failed (422): ${msg}`);
      } else {
        setGeneralError(msg || 'Failed to dispatch notification');
      }
    } finally {
      setSubmitting(false);
    }
  };

  // Preview content for template mode
  const previewSubject = activeVersion
    ? interpolateTemplateVariables(activeVersion.subject || '', templateVariables)
    : '';
  const previewBody = activeVersion
    ? interpolateTemplateVariables(activeVersion.body || '', templateVariables)
    : '';

  return (
    <div className="page-container" style={{ maxWidth: 680 }}>
      {/* Header and Back Link */}
      <div style={{ marginBottom: 20 }}>
        <Link
          to="/app/notifications"
          className="btn btn-ghost btn-sm"
          style={{
            padding: 0,
            marginBottom: 10,
            color: 'var(--color-signal)',
            textDecoration: 'none',
            display: 'inline-flex',
            gap: 6,
          }}
        >
          <ArrowLeft size={14} /> Back to Notifications
        </Link>
        <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)', marginBottom: 4 }}>
          Dispatch Notification
        </h1>
        <p style={{ color: 'var(--color-ink-2)', fontSize: 13 }}>
          Atomic durable acceptance into PostgreSQL transactional outbox. Returns 202 Accepted.
        </p>
      </div>

      {/* Idempotent Replay Banner */}
      {replayBanner && (
        <div
          className="panel"
          style={{
            padding: 16,
            marginBottom: 20,
            backgroundColor: 'var(--color-st-retrying-bg)',
            borderColor: 'var(--color-st-retrying-border)',
            color: 'var(--color-st-retrying)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <AlertTriangle size={18} />
            <div>
              <strong style={{ display: 'block', fontSize: 13 }}>Matching Idempotent Replay (202):</strong>
              <span style={{ fontSize: 12 }}>
                This idempotency key was already processed. Showing original notification.
              </span>
            </div>
          </div>
          {replayBanner.id && (
            <Link
              to={`/app/notifications/${replayBanner.id}`}
              className="btn btn-ghost btn-sm"
              style={{
                color: 'var(--color-signal)',
                fontWeight: 600,
              }}
            >
              View Notification →
            </Link>
          )}
        </div>
      )}

      {/* General Error Banner */}
      {generalError && (
        <div
          className="panel"
          style={{
            padding: 14,
            marginBottom: 20,
            backgroundColor: 'var(--color-st-failed-bg)',
            borderColor: 'var(--color-st-failed-border)',
            color: 'var(--color-st-failed)',
            fontSize: 13,
          }}
        >
          {generalError}
          {rateLimitCountdown !== null && (
            <span style={{ display: 'block', marginTop: 4, fontWeight: 600 }}>
              Retry available in {rateLimitCountdown} seconds.
            </span>
          )}
        </div>
      )}

      {/* Main Single-Column Form */}
      <form onSubmit={handleSubmit}>
        {/* Section 1: Channel Selector */}
        <div className="panel" style={{ padding: 20, marginBottom: 16 }}>
          <label style={{ display: 'block', fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 10 }}>
            1. Notification Channel
          </label>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 12,
            }}
          >
            {(['EMAIL', 'WEBHOOK', 'IN_APP'] as Channel[]).map((ch) => {
              const active = channel === ch;
              return (
                <button
                  key={ch}
                  type="button"
                  onClick={() => setChannel(ch)}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 8,
                    padding: '14px 12px',
                    borderRadius: 'var(--radius-control)',
                    border: active ? '1.5px solid var(--color-signal)' : '1px solid var(--color-rail)',
                    backgroundColor: active ? 'var(--color-signal-subtle)' : 'var(--color-paper)',
                    color: active ? 'var(--color-signal)' : 'var(--color-harbor)',
                    cursor: 'pointer',
                    transition: 'all 0.15s ease-out',
                  }}
                >
                  {ch === 'EMAIL' && <Mail size={20} />}
                  {ch === 'WEBHOOK' && <Webhook size={20} />}
                  {ch === 'IN_APP' && <Bell size={20} />}
                  <span style={{ fontSize: 13, fontWeight: active ? 700 : 600 }}>
                    {ch === 'EMAIL' ? 'Email' : ch === 'WEBHOOK' ? 'Webhook' : 'In-App'}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Section 2: Recipient Target */}
        <div className="panel" style={{ padding: 20, marginBottom: 16 }}>
          <label
            htmlFor="recipient-input"
            style={{ display: 'block', fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 4 }}
          >
            2. Recipient Target
          </label>
          <p style={{ fontSize: 12, color: 'var(--color-ink-3)', marginBottom: 10 }}>
            {channel === 'EMAIL' && 'Customer destination email address.'}
            {channel === 'WEBHOOK' && 'Endpoint HTTPS destination URL.'}
            {channel === 'IN_APP' && 'User or device reference ID for real-time inbox.'}
          </p>
          <input
            id="recipient-input"
            type={channel === 'EMAIL' ? 'email' : 'text'}
            value={recipient}
            onChange={(e) => {
              setRecipient(e.target.value);
              if (recipientError) validateRecipient();
            }}
            onBlur={validateRecipient}
            placeholder={
              channel === 'EMAIL'
                ? 'user@example.com'
                : channel === 'WEBHOOK'
                ? 'https://api.example.com/webhook'
                : 'usr_98412 or device token'
            }
            className={channel === 'EMAIL' || channel === 'WEBHOOK' ? 'mono' : ''}
            style={{
              borderColor: recipientError ? 'var(--color-st-failed)' : undefined,
            }}
          />
          {recipientError && (
            <span style={{ color: 'var(--color-st-failed)', fontSize: 12, marginTop: 4, display: 'block' }}>
              {recipientError}
            </span>
          )}
        </div>

        {/* Section 3: Content (Custom vs Template) */}
        <div className="panel" style={{ padding: 20, marginBottom: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
            <label style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
              3. Message Content
            </label>
            <div
              style={{
                display: 'inline-flex',
                backgroundColor: 'var(--color-subtle)',
                borderRadius: 'var(--radius-control)',
                padding: 2,
                border: '1px solid var(--color-rail)',
              }}
            >
              <button
                type="button"
                onClick={() => setContentMode('custom')}
                style={{
                  padding: '4px 10px',
                  fontSize: 12,
                  fontWeight: contentMode === 'custom' ? 600 : 500,
                  backgroundColor: contentMode === 'custom' ? 'var(--color-paper)' : 'transparent',
                  color: contentMode === 'custom' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                  border: 'none',
                  borderRadius: 4,
                  cursor: 'pointer',
                  boxShadow: contentMode === 'custom' ? '0 1px 2px rgba(0, 0, 0, 0.06)' : 'none',
                }}
              >
                Custom Message
              </button>
              <button
                type="button"
                onClick={() => setContentMode('template')}
                style={{
                  padding: '4px 10px',
                  fontSize: 12,
                  fontWeight: contentMode === 'template' ? 600 : 500,
                  backgroundColor: contentMode === 'template' ? 'var(--color-paper)' : 'transparent',
                  color: contentMode === 'template' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                  border: 'none',
                  borderRadius: 4,
                  cursor: 'pointer',
                  boxShadow: contentMode === 'template' ? '0 1px 2px rgba(0, 0, 0, 0.06)' : 'none',
                }}
              >
                Use Template
              </button>
            </div>
          </div>

          {contentMode === 'custom' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {channel === 'EMAIL' && (
                <div className="form-group">
                  <label>Email Subject:</label>
                  <input
                    type="text"
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    placeholder="Notification Subject"
                  />
                </div>
              )}

              <div className="form-group">
                <label>Message Body / Payload:</label>
                <textarea
                  rows={6}
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  placeholder="Notification content / payload..."
                  style={{
                    fontFamily: channel === 'EMAIL' ? 'var(--font-sans)' : 'var(--font-mono)',
                  }}
                />
              </div>
            </div>
          ) : (
            <div>
              {/* Template Selectors */}
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 140px', gap: 12, marginBottom: 14 }}>
                <div className="form-group">
                  <label>Select Template:</label>
                  <select
                    value={selectedTemplateId}
                    onChange={(e) => setSelectedTemplateId(e.target.value)}
                  >
                    <option value="">Choose a template…</option>
                    {templates.map((tpl) => (
                      <option key={tpl.id} value={tpl.id}>
                        {tpl.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="form-group">
                  <label>Version:</label>
                  <select
                    value={selectedVersionNumber}
                    disabled={!templateDetail}
                    onChange={(e) => handleVersionChange(Number(e.target.value))}
                  >
                    {templateDetail?.versions.map((ver) => (
                      <option key={ver.versionNumber} value={ver.versionNumber}>
                        v{ver.versionNumber} ({ver.status})
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {/* Dynamic Variables Inputs */}
              {activeVersion && (
                <div style={{ marginTop: 16 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-harbor)', display: 'block', marginBottom: 8 }}>
                    Template Variables:
                  </span>
                  {Object.keys(templateVariables).length === 0 ? (
                    <span style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>No variables detected in this version.</span>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {Object.keys(templateVariables).map((varKey) => (
                        <div key={varKey} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <span
                            className="mono"
                            style={{
                              width: 140,
                              fontSize: 12,
                              fontWeight: 600,
                              color: 'var(--color-signal)',
                            }}
                          >
                            {`{{${varKey}}}`}:
                          </span>
                          <input
                            type="text"
                            value={templateVariables[varKey] || ''}
                            onChange={(e) =>
                              setTemplateVariables((prev) => ({
                                ...prev,
                                [varKey]: e.target.value,
                              }))
                            }
                            placeholder={`Value for ${varKey}`}
                          />
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Live Render Preview */}
                  <div style={{ marginTop: 16, borderTop: '1px solid var(--color-rail)', paddingTop: 14 }}>
                    <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-ink-3)', display: 'block', marginBottom: 6 }}>
                      Live Rendered Preview:
                    </span>
                    {previewSubject && (
                      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8, color: 'var(--color-harbor)' }}>
                        Subject: {previewSubject}
                      </div>
                    )}
                    {channel === 'EMAIL' ? (
                      <SandboxedIframe content={previewBody} minHeight={180} />
                    ) : (
                      <pre
                        className="code-excerpt"
                        style={{
                          padding: 12,
                          maxHeight: 180,
                          overflowY: 'auto',
                        }}
                      >
                        {previewBody}
                      </pre>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Section 4: When to Dispatch */}
        <div className="panel" style={{ padding: 20, marginBottom: 16 }}>
          <label style={{ display: 'block', fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 8 }}>
            4. Dispatch Timing
          </label>
          <div style={{ display: 'flex', gap: 16, marginBottom: 12 }}>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer', color: 'var(--color-harbor)' }}>
              <input
                type="radio"
                name="dispatchMode"
                checked={dispatchMode === 'immediate'}
                onChange={() => setDispatchMode('immediate')}
              />
              <span>Send Immediately</span>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer', color: 'var(--color-harbor)' }}>
              <input
                type="radio"
                name="dispatchMode"
                checked={dispatchMode === 'schedule'}
                onChange={() => setDispatchMode('schedule')}
              />
              <span>Schedule for Later</span>
            </label>
          </div>

          {dispatchMode === 'schedule' && (
            <div className="form-group">
              <label>Target Datetime (Up to 90 days ahead):</label>
              <input
                type="datetime-local"
                value={scheduledAt}
                onChange={(e) => {
                  setScheduledAt(e.target.value);
                  if (scheduleError) validateSchedule();
                }}
                onBlur={validateSchedule}
                style={{
                  maxWidth: 280,
                  borderColor: scheduleError ? 'var(--color-st-failed)' : undefined,
                }}
              />
              {scheduleError && (
                <span style={{ color: 'var(--color-st-failed)', fontSize: 12, marginTop: 4, display: 'block' }}>
                  {scheduleError}
                </span>
              )}
            </div>
          )}
        </div>

        {/* Section 5: Idempotency Key */}
        <div className="panel" style={{ padding: 20, marginBottom: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <label style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
              5. Idempotency Key (Optional)
            </label>
            <button
              type="button"
              onClick={handleGenerateUuid}
              className="btn btn-ghost btn-sm"
              style={{
                color: 'var(--color-signal)',
                gap: 4,
                padding: '2px 6px',
              }}
            >
              <Sparkles size={13} />
              Generate UUID
            </button>
          </div>
          <p style={{ fontSize: 12, color: 'var(--color-ink-3)', marginBottom: 10 }}>
            Reuse this key to safely retry requests without generating duplicate messages.
          </p>
          <input
            type="text"
            value={idempotencyKey}
            onChange={(e) => setIdempotencyKey(e.target.value)}
            placeholder="e.g. order-ack-40918"
            className="mono"
          />
        </div>

        {/* Section 6: Metadata Attributes (Collapsible JSON) */}
        <div className="panel" style={{ padding: 20, marginBottom: 24 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
              6. Metadata Attributes (Optional JSON)
            </label>
            <button
              type="button"
              onClick={() => setMetadataExpanded((prev) => !prev)}
              className="btn btn-ghost btn-sm"
              style={{
                color: 'var(--color-signal)',
              }}
            >
              {metadataExpanded ? 'Collapse' : 'Expand Editor'}
            </button>
          </div>

          {metadataExpanded && (
            <div style={{ marginTop: 12 }}>
              <textarea
                rows={4}
                value={metadataJson}
                onChange={(e) => setMetadataJson(e.target.value)}
                placeholder={'{\n  "orderId": "ord_9182",\n  "customerId": "cust_12"\n}'}
                className="mono"
                style={{
                  borderColor: metadataError ? 'var(--color-st-failed)' : undefined,
                }}
              />
              {metadataError && (
                <span style={{ color: 'var(--color-st-failed)', fontSize: 12, marginTop: 4, display: 'block' }}>
                  {metadataError}
                </span>
              )}
            </div>
          )}
        </div>

        {/* Submit Bar */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, alignItems: 'center' }}>
          <button
            type="button"
            onClick={() => navigate('/app/notifications')}
            className="btn btn-secondary"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={submitting || isSuspended}
            className="btn btn-primary"
            style={{ padding: '10px 22px' }}
          >
            {submitting ? 'Dispatching…' : dispatchMode === 'schedule' ? 'Schedule Notification' : 'Send Notification'}
          </button>
        </div>
      </form>
    </div>
  );
}
