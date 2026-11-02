import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  getTemplate,
  createDraftVersion,
  updateDraftVersion,
  publishVersion,
  type TemplateDetail,
  type TemplateVersion,
  type TemplateContentType,
} from '../api/templates';
import { extractTemplateVariables, interpolateTemplateVariables } from '../components/templateVariableUtils';
import SandboxedIframe from '../components/SandboxedIframe';
import { getChannelIcon } from '../components/FilterBar';
import {
  ArrowLeft,
  Lock,
  Save,
  Send,
  Plus,
  Eye,
  Edit3,
  CheckCircle2,
  AlertCircle,
  Clock,
  Sparkles,
} from 'lucide-react';

export default function TemplateDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const isSuspended = user?.tenantStatus === 'SUSPENDED';

  const [template, setTemplate] = useState<TemplateDetail | null>(null);
  const [activeVersionNumber, setActiveVersionNumber] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Editor State
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [contentType, setContentType] = useState<TemplateContentType>('TEXT');
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [editorError, setEditorError] = useState<string | null>(null);

  // Preview tab state
  const [viewTab, setViewTab] = useState<'editor' | 'preview'>('editor');
  const [previewVariables, setPreviewVariables] = useState<Record<string, string>>({});

  // Publish Dialog state
  const [showPublishModal, setShowPublishModal] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);

  const fetchTemplateDetail = async (templateId: string, preferredVersionNumber?: number) => {
    try {
      setLoading(true);
      setError(null);
      const data = await getTemplate(templateId);
      setTemplate(data);

      const targetVersionNum =
        preferredVersionNumber ??
        data.currentVersion?.versionNumber ??
        (data.versions.length > 0 ? data.versions[0].versionNumber : null);

      if (targetVersionNum !== null) {
        selectVersion(data, targetVersionNum);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load template');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (id) {
      fetchTemplateDetail(id);
    }
  }, [id]);

  const selectVersion = (data: TemplateDetail, verNum: number) => {
    setActiveVersionNumber(verNum);
    const ver = data.versions.find((v) => v.versionNumber === verNum);
    if (ver) {
      setSubject(ver.subject || '');
      setBody(ver.body || '');
      setContentType(ver.contentType || 'TEXT');

      const detected = ver.variables || extractTemplateVariables(ver.subject, ver.body);
      const initialVars: Record<string, string> = {};
      for (const k of detected) {
        initialVars[k] = previewVariables[k] || '';
      }
      setPreviewVariables(initialVars);
    }
  };

  const activeVersion: TemplateVersion | undefined = template?.versions.find(
    (v) => v.versionNumber === activeVersionNumber
  );

  const isImmutable = activeVersion?.status === 'PUBLISHED' || activeVersion?.status === 'ARCHIVED';

  // Detected variables in current editor content
  const detectedVariables = extractTemplateVariables(subject, body);

  const handleSaveDraft = async () => {
    if (!id || !activeVersionNumber || isImmutable) return;
    try {
      setSaving(true);
      setEditorError(null);
      setSaveSuccess(false);

      await updateDraftVersion(id, activeVersionNumber, {
        subject: template?.channel === 'EMAIL' ? subject.trim() || undefined : undefined,
        body: body.trim(),
        contentType,
      });

      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 2500);
      await fetchTemplateDetail(id, activeVersionNumber);
    } catch (err: any) {
      setEditorError(err.message || 'Failed to save draft version');
    } finally {
      setSaving(false);
    }
  };

  const handleCreateDraftFromVersion = async (sourceVersionNumber: number) => {
    if (!id || isSuspended) return;
    try {
      setSaving(true);
      setEditorError(null);
      const newVersion = await createDraftVersion(id, {
        cloneFromVersion: sourceVersionNumber,
      });
      await fetchTemplateDetail(id, newVersion.versionNumber);
    } catch (err: any) {
      setEditorError(err.message || 'Failed to create new draft version');
    } finally {
      setSaving(false);
    }
  };

  const handlePublishSubmit = async () => {
    if (!id || !activeVersionNumber) return;
    try {
      setPublishing(true);
      setPublishError(null);
      await publishVersion(id, activeVersionNumber);
      setShowPublishModal(false);
      await fetchTemplateDetail(id, activeVersionNumber);
    } catch (err: any) {
      setPublishError(err.message || 'Failed to publish version');
    } finally {
      setPublishing(false);
    }
  };

  if (loading) {
    return (
      <div className="page-container" style={{ maxWidth: 1200 }}>
        <div className="panel" style={{ padding: 56, textAlign: 'center', color: 'var(--color-ink-2)' }}>
          <div className="loading-spinner" style={{ margin: '0 auto 14px' }} />
          <p style={{ margin: 0, fontSize: 13, fontWeight: 500 }}>Loading template details…</p>
        </div>
      </div>
    );
  }

  if (error || !template) {
    return (
      <div className="page-container" style={{ maxWidth: 1200 }}>
        <div style={{ marginBottom: 16 }}>
          <Link to="/app/templates" className="btn btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
            <ArrowLeft size={13} /> Back to Templates
          </Link>
        </div>
        <div className="panel" style={{ padding: 24, borderLeft: '3px solid var(--color-st-failed)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-st-failed)', fontWeight: 600, marginBottom: 4 }}>
            <AlertCircle size={16} /> Unable to load template
          </div>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--color-ink-2)' }}>{error || 'Template not found'}</p>
        </div>
      </div>
    );
  }

  const renderedPreviewSubject = interpolateTemplateVariables(subject, previewVariables);
  const renderedPreviewBody = interpolateTemplateVariables(body, previewVariables);

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Top Header / Breadcrumb Bar */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Link
            to="/app/templates"
            className="btn btn-secondary"
            style={{ padding: '6px 10px', fontSize: 12, textDecoration: 'none' }}
          >
            <ArrowLeft size={13} /> Templates
          </Link>
          <span style={{ color: 'var(--color-rail)' }}>/</span>
          <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-harbor)' }}>
            {template.name}
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              fontSize: 11,
              fontWeight: 600,
              backgroundColor: 'var(--color-chalk)',
              color: 'var(--color-harbor)',
              padding: '4px 10px',
              borderRadius: 'var(--radius-control)',
              border: '1px solid var(--color-rail)',
              textTransform: 'uppercase',
              letterSpacing: '0.04em',
            }}
          >
            {getChannelIcon(template.channel as any, 13)}
            <span>{template.channel}</span>
          </span>
        </div>
      </div>

      {/* Main Two-Column Layout (Left: Versions Rail 280px, Right: Editor) */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '280px minmax(0, 1fr)',
          gap: 20,
          alignItems: 'start',
        }}
        className="detail-grid"
      >
        {/* Left Column: Version History Rail */}
        <div className="panel" style={{ padding: 18 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
            <div>
              <h2 style={{ fontSize: 13, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
                Version History
              </h2>
              <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>
                {template.versions.length} {template.versions.length === 1 ? 'revision' : 'revisions'}
              </span>
            </div>
            <button
              type="button"
              onClick={() => handleCreateDraftFromVersion(activeVersionNumber || 1)}
              disabled={isSuspended}
              className="btn btn-secondary"
              style={{ padding: '4px 8px', fontSize: 11 }}
              title="Create new draft from active version"
            >
              <Plus size={12} /> New Draft
            </button>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {template.versions.map((ver) => {
              const isActive = ver.versionNumber === activeVersionNumber;
              const isPub = ver.status === 'PUBLISHED';
              const isArch = ver.status === 'ARCHIVED';

              return (
                <button
                  key={ver.id}
                  type="button"
                  onClick={() => selectVersion(template, ver.versionNumber)}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'flex-start',
                    padding: '10px 12px',
                    borderRadius: 'var(--radius-control)',
                    border: isActive
                      ? '1px solid var(--color-signal)'
                      : '1px solid var(--color-rail)',
                    backgroundColor: isActive
                      ? 'var(--color-st-processing-bg)'
                      : 'var(--color-paper)',
                    cursor: 'pointer',
                    textAlign: 'left',
                    width: '100%',
                    transition: 'border-color 0.15s ease, background-color 0.15s ease',
                    position: 'relative',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%', alignItems: 'center' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: isActive ? 'var(--color-signal)' : 'var(--color-harbor)' }}>
                        v{ver.versionNumber}
                      </span>
                      {template.currentVersion?.versionNumber === ver.versionNumber && (
                        <span style={{ fontSize: 9, fontWeight: 700, textTransform: 'uppercase', padding: '1px 5px', borderRadius: 3, background: 'var(--color-chalk)', color: 'var(--color-ink-2)' }}>
                          Active
                        </span>
                      )}
                    </div>
                    <span
                      className={`chip ${
                        isPub
                          ? 'chip-delivered'
                          : isArch
                          ? 'chip-cancelled'
                          : 'chip-retrying'
                      }`}
                      style={{ fontSize: 10, padding: '1px 6px' }}
                    >
                      {ver.status}
                    </span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 6, fontSize: 11, color: 'var(--color-ink-2)' }}>
                    <Clock size={11} />
                    <span className="mono" style={{ fontSize: 10 }}>
                      {new Date(ver.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}
                    </span>
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        {/* Right Column: Editor & Live Preview */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Header Card */}
          <div className="panel" style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 16 }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <h1 style={{ fontSize: 18, fontWeight: 700, margin: 0, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
                    {template.name}
                  </h1>
                  <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-signal)', padding: '2px 8px', borderRadius: 'var(--radius-control)', backgroundColor: 'var(--color-st-processing-bg)' }}>
                    v{activeVersionNumber}
                  </span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12, color: 'var(--color-ink-2)' }}>
                  <span>Template ID: <code className="mono" style={{ fontSize: 11, color: 'var(--color-harbor)' }}>{template.id}</code></span>
                  {template.description && (
                    <>
                      <span>•</span>
                      <span>{template.description}</span>
                    </>
                  )}
                </div>
              </div>

              {/* Action Buttons */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {activeVersion?.status === 'DRAFT' && (
                  <>
                    <button
                      type="button"
                      onClick={handleSaveDraft}
                      disabled={saving || isSuspended}
                      className="btn btn-secondary"
                      style={{ fontSize: 12 }}
                    >
                      <Save size={13} />
                      {saving ? 'Saving…' : 'Save Draft'}
                    </button>

                    <button
                      type="button"
                      onClick={() => setShowPublishModal(true)}
                      disabled={isSuspended}
                      className="btn btn-primary"
                      style={{ fontSize: 12 }}
                    >
                      <Send size={13} />
                      Publish Version
                    </button>
                  </>
                )}

                {isImmutable && (
                  <button
                    type="button"
                    onClick={() => handleCreateDraftFromVersion(activeVersionNumber || 1)}
                    disabled={isSuspended}
                    className="btn btn-secondary"
                    style={{ fontSize: 12 }}
                  >
                    <Plus size={13} />
                    New Draft from v{activeVersionNumber}
                  </button>
                )}
              </div>
            </div>

            {/* Immutability Banner */}
            {isImmutable && (
              <div
                style={{
                  marginTop: 16,
                  padding: '10px 14px',
                  backgroundColor: 'var(--color-chalk)',
                  border: '1px solid var(--color-rail)',
                  borderRadius: 'var(--radius-control)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  fontSize: 12,
                  color: 'var(--color-ink-2)',
                }}
              >
                <Lock size={14} style={{ color: 'var(--color-ink-2)', flexShrink: 0 }} />
                <span>
                  <strong style={{ color: 'var(--color-harbor)' }}>Immutable Version:</strong> Published versions are locked to guarantee delivery reproducibility. Create a new draft revision to modify content or variables.
                </span>
              </div>
            )}

            {saveSuccess && (
              <div
                style={{
                  marginTop: 14,
                  padding: '9px 13px',
                  backgroundColor: 'var(--color-st-delivered-bg)',
                  border: '1px solid var(--color-st-delivered)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-delivered)',
                  fontSize: 12,
                  fontWeight: 500,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <CheckCircle2 size={14} />
                Draft changes saved successfully.
              </div>
            )}

            {editorError && (
              <div
                style={{
                  marginTop: 14,
                  padding: '9px 13px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  border: '1px solid var(--color-st-failed)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <AlertCircle size={14} />
                {editorError}
              </div>
            )}
          </div>

          {/* Editor & Preview Tabs */}
          <div className="panel" style={{ padding: 20 }}>
            {/* View Mode Switcher + Detected Variables */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
              <div
                style={{
                  display: 'inline-flex',
                  backgroundColor: 'var(--color-chalk)',
                  borderRadius: 'var(--radius-control)',
                  padding: 2,
                  border: '1px solid var(--color-rail)',
                }}
              >
                <button
                  type="button"
                  onClick={() => setViewTab('editor')}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 5,
                    padding: '5px 12px',
                    fontSize: 12,
                    fontWeight: viewTab === 'editor' ? 600 : 500,
                    backgroundColor: viewTab === 'editor' ? 'var(--color-paper)' : 'transparent',
                    color: viewTab === 'editor' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                    boxShadow: viewTab === 'editor' ? 'var(--shadow-card)' : 'none',
                  }}
                >
                  <Edit3 size={13} />
                  Editor
                </button>
                <button
                  type="button"
                  onClick={() => setViewTab('preview')}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 5,
                    padding: '5px 12px',
                    fontSize: 12,
                    fontWeight: viewTab === 'preview' ? 600 : 500,
                    backgroundColor: viewTab === 'preview' ? 'var(--color-paper)' : 'transparent',
                    color: viewTab === 'preview' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                    boxShadow: viewTab === 'preview' ? 'var(--shadow-card)' : 'none',
                  }}
                >
                  <Eye size={13} />
                  Live Preview
                </button>
              </div>

              {/* Detected Variables Chips */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 11, color: 'var(--color-ink-2)', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                  <Sparkles size={12} style={{ color: 'var(--color-signal)' }} /> Detected:
                </span>
                {detectedVariables.length === 0 ? (
                  <span style={{ fontSize: 11, color: 'var(--color-ink-2)', fontStyle: 'italic' }}>None</span>
                ) : (
                  detectedVariables.map((v) => (
                    <span
                      key={v}
                      className="mono"
                      style={{
                        fontSize: 11,
                        padding: '2px 7px',
                        backgroundColor: 'var(--color-st-processing-bg)',
                        color: 'var(--color-signal)',
                        borderRadius: 4,
                        fontWeight: 600,
                        border: '1px solid rgba(13, 148, 136, 0.2)',
                      }}
                    >
                      {`{{${v}}}`}
                    </span>
                  ))
                )}
              </div>
            </div>

            {viewTab === 'editor' ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {/* Subject Line (EMAIL only) */}
                {template.channel === 'EMAIL' && (
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>
                      Subject Line:
                    </label>
                    <input
                      type="text"
                      disabled={isImmutable}
                      value={subject}
                      onChange={(e) => setSubject(e.target.value)}
                      placeholder="Welcome to {{company}}!"
                      className="input-text"
                      style={{
                        backgroundColor: isImmutable ? 'var(--color-chalk)' : 'var(--color-paper)',
                        opacity: isImmutable ? 0.75 : 1,
                      }}
                    />
                  </div>
                )}

                {/* Body Content */}
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                    <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                      Message Body:
                    </label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>Format:</span>
                      <select
                        disabled={isImmutable}
                        value={contentType}
                        onChange={(e) => setContentType(e.target.value as TemplateContentType)}
                        className="select-control"
                        style={{ fontSize: 11, padding: '3px 8px', width: 'auto' }}
                      >
                        <option value="TEXT">Plain Text</option>
                        <option value="HTML">HTML</option>
                      </select>
                    </div>
                  </div>
                  <textarea
                    rows={14}
                    disabled={isImmutable}
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    placeholder="Enter template body with optional {{variables}}..."
                    className="input-text mono"
                    style={{
                      lineHeight: 1.6,
                      fontSize: 12,
                      backgroundColor: isImmutable ? 'var(--color-chalk)' : 'var(--color-paper)',
                      opacity: isImmutable ? 0.75 : 1,
                      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
                    }}
                  />
                </div>
              </div>
            ) : (
              <div>
                {/* Live Preview Mode */}
                <div style={{ marginBottom: 20 }}>
                  <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 10 }}>
                    Sample Variable Bindings:
                  </div>
                  {detectedVariables.length === 0 ? (
                    <p style={{ fontSize: 12, color: 'var(--color-ink-2)', fontStyle: 'italic', margin: 0 }}>
                      No variables detected in this revision. Type <code>&#123;&#123;variableName&#125;&#125;</code> in the editor to bind dynamic fields.
                    </p>
                  ) : (
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 10 }}>
                      {detectedVariables.map((vKey) => (
                        <div key={vKey}>
                          <label className="mono" style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--color-signal)', marginBottom: 4 }}>
                            {`{{${vKey}}}`}
                          </label>
                          <input
                            type="text"
                            value={previewVariables[vKey] || ''}
                            onChange={(e) =>
                              setPreviewVariables((prev) => ({
                                ...prev,
                                [vKey]: e.target.value,
                              }))
                            }
                            placeholder={`Value for ${vKey}`}
                            className="input-text"
                            style={{ fontSize: 12, padding: '6px 10px' }}
                          />
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {/* Rendered Preview Container */}
                <div style={{ borderTop: '1px solid var(--color-rail)', paddingTop: 16 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                    <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                      Interpolated Output Preview:
                    </span>
                    <span style={{ fontSize: 11, color: 'var(--color-ink-2)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      {contentType}
                    </span>
                  </div>

                  {renderedPreviewSubject && (
                    <div style={{ padding: '8px 12px', background: 'var(--color-chalk)', border: '1px solid var(--color-rail)', borderRadius: 'var(--radius-control)', marginBottom: 10, fontSize: 13, fontWeight: 600, color: 'var(--color-harbor)' }}>
                      <span style={{ color: 'var(--color-ink-2)', fontWeight: 500, marginRight: 6 }}>Subject:</span>
                      {renderedPreviewSubject}
                    </div>
                  )}

                  {template.channel === 'EMAIL' && contentType === 'HTML' ? (
                    <div style={{ border: '1px solid var(--color-rail)', borderRadius: 'var(--radius-control)', overflow: 'hidden' }}>
                      <SandboxedIframe content={renderedPreviewBody} minHeight={260} />
                    </div>
                  ) : (
                    <pre
                      className="mono"
                      style={{
                        padding: 16,
                        backgroundColor: 'var(--color-chalk)',
                        borderRadius: 'var(--radius-control)',
                        border: '1px solid var(--color-rail)',
                        fontSize: 12,
                        lineHeight: 1.6,
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                        margin: 0,
                        color: 'var(--color-harbor)',
                      }}
                    >
                      {renderedPreviewBody || <span style={{ color: 'var(--color-ink-2)', fontStyle: 'italic' }}>Template body is empty</span>}
                    </pre>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Publish Confirm Dialog */}
      {showPublishModal && (
        <div className="modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 440 }}>
            <div className="modal-header">
              <h3 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                Publish Version {activeVersionNumber}?
              </h3>
            </div>
            <div className="modal-body">
              <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.6, margin: 0 }}>
                Publishing v{activeVersionNumber} will make it the active, immutable production default for new notifications dispatched with this template. Any prior published version will be safely archived.
              </p>
              {publishError && (
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
                  {publishError}
                </div>
              )}
            </div>
            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setShowPublishModal(false)}
                className="btn btn-secondary"
              >
                Keep as Draft
              </button>
              <button
                type="button"
                onClick={handlePublishSubmit}
                disabled={publishing}
                className="btn btn-primary"
              >
                {publishing ? 'Publishing…' : `Publish v${activeVersionNumber}`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}