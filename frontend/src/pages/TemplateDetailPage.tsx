import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  getTemplate,
  createDraftVersion,
  updateDraftVersion,
  publishVersion,
  deleteTemplateVersion,
  deleteTemplateVersions,
  type TemplateDetail,
  type TemplateVersion,
  type TemplateContentType,
  type CreateVersionRequest,
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
  X,
  Trash2,
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

  // New Version Modal state
  const [showNewVersionModal, setShowNewVersionModal] = useState(false);
  const [newVersionCloneSource, setNewVersionCloneSource] = useState<number | 'blank'>(1);
  const [newVersionSubject, setNewVersionSubject] = useState('');
  const [newVersionBody, setNewVersionBody] = useState('');
  const [newVersionContentType, setNewVersionContentType] = useState<TemplateContentType>('TEXT');
  const [creatingVersion, setCreatingVersion] = useState(false);
  const [createVersionError, setCreateVersionError] = useState<string | null>(null);

  // Delete Version Modal & Selection State
  const [selectedVersionNumbers, setSelectedVersionNumbers] = useState<number[]>([]);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [versionsToDelete, setVersionsToDelete] = useState<number[]>([]);
  const [deletingVersion, setDeletingVersion] = useState(false);
  const [deleteVersionError, setDeleteVersionError] = useState<string | null>(null);

  const fetchTemplateDetail = async (templateId: string, preferredVersionNumber?: number) => {
    try {
      setLoading(true);
      setError(null);
      const data = await getTemplate(templateId);
      setTemplate(data);

      const targetVersionNum =
        preferredVersionNumber !== undefined
          ? preferredVersionNumber
          : (data.versions.find((v) => v.status === 'PUBLISHED')?.versionNumber ??
             (data.versions.length > 0 ? data.versions[0].versionNumber : null));

      if (targetVersionNum !== null && targetVersionNum !== undefined) {
        selectVersion(data, targetVersionNum);
      } else {
        setActiveVersionNumber(null);
        setSubject('');
        setBody('');
        setPreviewVariables({});
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

  const isDirty = activeVersion?.status === 'DRAFT' && (
    (activeVersion.subject || '') !== subject ||
    (activeVersion.body || '') !== body ||
    (activeVersion.contentType || 'TEXT') !== contentType
  );

  const handleSelectVersion = (verNum: number) => {
    if (verNum === activeVersionNumber) return;
    if (isDirty) {
      const confirmDiscard = window.confirm(
        'You have unsaved changes in the current draft. Discard changes and switch version?'
      );
      if (!confirmDiscard) return;
    }
    if (template) {
      selectVersion(template, verNum);
    }
  };

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

  const openNewVersionModal = (preferredSourceVer?: number) => {
    const hasVersions = template?.versions && template.versions.length > 0;
    const defaultSource = hasVersions ? template!.versions[0].versionNumber : 'blank';
    const srcNum = preferredSourceVer ?? (activeVersionNumber !== null ? activeVersionNumber : defaultSource);
    const srcVer = typeof srcNum === 'number' ? template?.versions.find(v => v.versionNumber === srcNum) : undefined;
    setNewVersionCloneSource(srcNum);

    // If cloning from the active version, prioritize the current editor state so in-progress inputs are preserved!
    if (typeof srcNum === 'number' && srcNum === activeVersionNumber && activeVersionNumber !== null) {
      setNewVersionSubject(subject);
      setNewVersionBody(body);
      setNewVersionContentType(contentType);
    } else if (srcVer) {
      setNewVersionSubject(srcVer.subject || '');
      setNewVersionBody(srcVer.body || '');
      setNewVersionContentType(srcVer.contentType || 'TEXT');
    } else {
      setNewVersionSubject('');
      setNewVersionBody('');
      setNewVersionContentType('TEXT');
    }

    setCreateVersionError(null);
    setShowNewVersionModal(true);
  };

  const handleToggleSelectAll = () => {
    if (!template) return;
    if (selectedVersionNumbers.length === template.versions.length) {
      setSelectedVersionNumbers([]);
    } else {
      setSelectedVersionNumbers(template.versions.map((v) => v.versionNumber));
    }
  };

  const toggleSelectVersion = (verNum: number) => {
    setSelectedVersionNumbers((prev) =>
      prev.includes(verNum) ? prev.filter((n) => n !== verNum) : [...prev, verNum]
    );
  };

  const openDeleteConfirmModal = (verNums: number[]) => {
    setVersionsToDelete(verNums);
    setDeleteVersionError(null);
    setShowDeleteModal(true);
  };

  const handleDeleteSubmit = async () => {
    if (!id || versionsToDelete.length === 0 || isSuspended) return;

    try {
      setDeletingVersion(true);
      setDeleteVersionError(null);

      if (versionsToDelete.length === 1) {
        await deleteTemplateVersion(id, versionsToDelete[0]);
      } else {
        await deleteTemplateVersions(id, versionsToDelete);
      }

      // Deselect deleted versions
      setSelectedVersionNumbers((prev) => prev.filter((n) => !versionsToDelete.includes(n)));

      // If active version was deleted, find next fallback version
      let nextVerNum: number | undefined = activeVersionNumber ?? undefined;
      if (activeVersionNumber && versionsToDelete.includes(activeVersionNumber)) {
        const remaining = (template?.versions || []).filter(
          (v) => !versionsToDelete.includes(v.versionNumber)
        );
        const pub = remaining.find((v) => v.status === 'PUBLISHED');
        nextVerNum = pub?.versionNumber ?? (remaining.length > 0 ? remaining[0].versionNumber : undefined);
      }

      setShowDeleteModal(false);
      await fetchTemplateDetail(id, nextVerNum);
    } catch (err: any) {
      setDeleteVersionError(err.message || 'Failed to delete template version(s)');
    } finally {
      setDeletingVersion(false);
    }
  };

  const handleCloneSourceChange = (val: string) => {
    if (val === 'blank') {
      setNewVersionCloneSource('blank');
      setNewVersionSubject('');
      setNewVersionBody('');
    } else {
      const num = Number(val);
      setNewVersionCloneSource(num);
      if (num === activeVersionNumber) {
        setNewVersionSubject(subject);
        setNewVersionBody(body);
        setNewVersionContentType(contentType);
      } else {
        const srcVer = template?.versions.find(v => v.versionNumber === num);
        if (srcVer) {
          setNewVersionSubject(srcVer.subject || '');
          setNewVersionBody(srcVer.body || '');
          setNewVersionContentType(srcVer.contentType || 'TEXT');
        }
      }
    }
  };

  const handleCreateVersionSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!id || isSuspended) return;

    if (!newVersionBody.trim()) {
      setCreateVersionError('Template body is required');
      return;
    }
    if (template?.channel === 'EMAIL' && !newVersionSubject.trim()) {
      setCreateVersionError('Subject is required for EMAIL templates');
      return;
    }

    try {
      setCreatingVersion(true);
      setCreateVersionError(null);

      const request: CreateVersionRequest = {
        cloneFromVersion: newVersionCloneSource === 'blank' ? undefined : Number(newVersionCloneSource),
        subject: template?.channel === 'EMAIL' ? newVersionSubject.trim() || undefined : undefined,
        body: newVersionBody.trim(),
        contentType: newVersionContentType,
      };

      const newVer = await createDraftVersion(id, request);
      setShowNewVersionModal(false);
      await fetchTemplateDetail(id, newVer.versionNumber);
    } catch (err: any) {
      setCreateVersionError(err.message || 'Failed to create new version');
    } finally {
      setCreatingVersion(false);
    }
  };

  const handlePublishSubmit = async () => {
    if (!id || !activeVersionNumber) return;
    try {
      setPublishing(true);
      setPublishError(null);

      // Auto-save the active draft before publishing so all recent edits are applied!
      if (activeVersion?.status === 'DRAFT') {
        if (!body.trim()) {
          setPublishError('Template body cannot be blank');
          setPublishing(false);
          return;
        }
        if (template?.channel === 'EMAIL' && !subject.trim()) {
          setPublishError('Subject is required for EMAIL templates');
          setPublishing(false);
          return;
        }

        await updateDraftVersion(id, activeVersionNumber, {
          subject: template?.channel === 'EMAIL' ? subject.trim() || undefined : undefined,
          body: body.trim(),
          contentType,
        });
      }

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
              onClick={() => openNewVersionModal(activeVersionNumber || 1)}
              disabled={isSuspended}
              className="btn btn-secondary"
              style={{ padding: '4px 8px', fontSize: 11 }}
              title="Create new draft version"
            >
              <Plus size={12} /> New Draft
            </button>
          </div>

          {template.versions.length === 0 ? (
            <div style={{ padding: '16px 8px', textAlign: 'center', color: 'var(--color-ink-2)', fontSize: 12 }}>
              No versions in this template.
            </div>
          ) : (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8, padding: '2px 4px', fontSize: 11, color: 'var(--color-ink-2)' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', userSelect: 'none' }}>
                  <input
                    type="checkbox"
                    checked={selectedVersionNumbers.length === template.versions.length && template.versions.length > 0}
                    onChange={handleToggleSelectAll}
                    style={{ cursor: 'pointer', margin: 0 }}
                    aria-label="Select all versions"
                  />
                  <span>Select All</span>
                </label>
                {selectedVersionNumbers.length > 0 && (
                  <button
                    type="button"
                    onClick={() => openDeleteConfirmModal(selectedVersionNumbers)}
                    disabled={isSuspended}
                    className="btn btn-secondary btn-sm"
                    style={{
                      padding: '2px 8px',
                      fontSize: 11,
                      color: 'var(--color-st-failed)',
                      borderColor: 'var(--color-st-failed)',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 4,
                    }}
                  >
                    <Trash2 size={11} />
                    Delete ({selectedVersionNumbers.length})
                  </button>
                )}
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {template.versions.map((ver) => {
                  const isActive = ver.versionNumber === activeVersionNumber;
                  const isPub = ver.status === 'PUBLISHED';
                  const isArch = ver.status === 'ARCHIVED';
                  const isSelected = selectedVersionNumbers.includes(ver.versionNumber);

                  return (
                    <div
                      key={ver.id}
                      onClick={() => handleSelectVersion(ver.versionNumber)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          handleSelectVersion(ver.versionNumber);
                        }
                      }}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                        padding: '10px 12px',
                        borderRadius: 'var(--radius-control)',
                        border: isActive
                          ? '1px solid var(--color-signal)'
                          : isSelected
                          ? '1px solid var(--color-ink-2)'
                          : '1px solid var(--color-rail)',
                        backgroundColor: isActive
                          ? 'var(--color-st-processing-bg)'
                          : isSelected
                          ? 'var(--color-chalk)'
                          : 'var(--color-paper)',
                        cursor: 'pointer',
                        textAlign: 'left',
                        width: '100%',
                        transition: 'border-color 0.15s ease, background-color 0.15s ease',
                        position: 'relative',
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={(e) => {
                          e.stopPropagation();
                          toggleSelectVersion(ver.versionNumber);
                        }}
                        onClick={(e) => e.stopPropagation()}
                        style={{ cursor: 'pointer', margin: 0, flexShrink: 0 }}
                        aria-label={`Select version ${ver.versionNumber}`}
                      />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%', alignItems: 'center' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                            <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: isActive ? 'var(--color-signal)' : 'var(--color-harbor)' }}>
                              v{ver.versionNumber}
                            </span>
                            {ver.status === 'PUBLISHED' && (
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
                      </div>

                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          openDeleteConfirmModal([ver.versionNumber]);
                        }}
                        disabled={isSuspended}
                        className="btn btn-ghost"
                        style={{
                          padding: 4,
                          color: 'var(--color-ink-2)',
                          borderRadius: 'var(--radius-control)',
                          flexShrink: 0,
                        }}
                        title={`Delete version v${ver.versionNumber}`}
                        aria-label={`Delete version v${ver.versionNumber}`}
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  );
                })}
              </div>
            </>
          )}
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
                  {activeVersionNumber !== null && (
                    <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-signal)', padding: '2px 8px', borderRadius: 'var(--radius-control)', backgroundColor: 'var(--color-st-processing-bg)' }}>
                      v{activeVersionNumber}
                    </span>
                  )}
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
                    onClick={() => openNewVersionModal(activeVersionNumber || 1)}
                    disabled={isSuspended}
                    className="btn btn-secondary"
                    style={{ fontSize: 12 }}
                  >
                    <Plus size={13} />
                    New Draft from v{activeVersionNumber}
                  </button>
                )}

                {activeVersion && (
                  <button
                    type="button"
                    onClick={() => openDeleteConfirmModal([activeVersion.versionNumber])}
                    disabled={isSuspended}
                    className="btn btn-secondary"
                    style={{
                      fontSize: 12,
                      color: 'var(--color-st-failed)',
                      borderColor: 'var(--color-rail)',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 5,
                    }}
                    title={`Delete version v${activeVersion.versionNumber}`}
                  >
                    <Trash2 size={13} />
                    Delete Version
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
          {!activeVersion ? (
            <div className="panel" style={{ padding: 48, textAlign: 'center' }}>
              <p style={{ fontSize: 14, color: 'var(--color-ink-2)', marginBottom: 16 }}>
                This template has no versions. Create a draft version to start composing.
              </p>
              <button
                type="button"
                onClick={() => openNewVersionModal()}
                disabled={isSuspended}
                className="btn btn-primary"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
              >
                <Plus size={14} /> Create Version
              </button>
            </div>
          ) : (
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
          )}
        </div>
      </div>

      {/* Publish Confirm Dialog */}
      {showPublishModal && (
        <div className="modal-overlay modal-backdrop">
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

      {/* Create New Version Dialog */}
      {showNewVersionModal && (
        <div className="modal-overlay modal-backdrop">
          <div className="modal-card" style={{ maxWidth: 540 }}>
            <form onSubmit={handleCreateVersionSubmit}>
              <div className="modal-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h3 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                  Create New Version (v{(template?.versions.length ? Math.max(...template.versions.map(v => v.versionNumber)) : 0) + 1} Draft)
                </h3>
                <button
                  type="button"
                  onClick={() => setShowNewVersionModal(false)}
                  style={{ border: 'none', background: 'transparent', cursor: 'pointer', padding: 4, color: 'var(--color-ink-2)' }}
                  aria-label="Close dialog"
                >
                  <X size={16} />
                </button>
              </div>
              <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>
                    Base / Clone Source:
                  </label>
                  <select
                    value={newVersionCloneSource}
                    onChange={(e) => handleCloneSourceChange(e.target.value)}
                    className="select-control"
                    style={{ width: '100%', fontSize: 12, padding: '7px 10px' }}
                  >
                    {template?.versions.map((v) => (
                      <option key={v.versionNumber} value={v.versionNumber}>
                        Clone from v{v.versionNumber} ({v.status}) {v.versionNumber === activeVersionNumber ? '— Current' : ''}
                      </option>
                    ))}
                    <option value="blank">Start Blank (No Clone)</option>
                  </select>
                </div>

                {template?.channel === 'EMAIL' && (
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 6 }}>
                      Subject Line:
                    </label>
                    <input
                      type="text"
                      value={newVersionSubject}
                      onChange={(e) => setNewVersionSubject(e.target.value)}
                      placeholder="Welcome to {{company}}!"
                      className="input-text"
                      style={{ width: '100%', fontSize: 12 }}
                    />
                  </div>
                )}

                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                    <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
                      Message Body:
                    </label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ fontSize: 11, color: 'var(--color-ink-2)' }}>Format:</span>
                      <select
                        value={newVersionContentType}
                        onChange={(e) => setNewVersionContentType(e.target.value as TemplateContentType)}
                        className="select-control"
                        style={{ fontSize: 11, padding: '3px 8px', width: 'auto' }}
                      >
                        <option value="TEXT">Plain Text</option>
                        <option value="HTML">HTML</option>
                      </select>
                    </div>
                  </div>
                  <textarea
                    rows={8}
                    value={newVersionBody}
                    onChange={(e) => setNewVersionBody(e.target.value)}
                    placeholder="Enter template body with optional {{variables}}..."
                    className="input-text mono"
                    style={{
                      width: '100%',
                      lineHeight: 1.5,
                      fontSize: 12,
                      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
                    }}
                  />
                </div>

                {createVersionError && (
                  <div
                    style={{
                      padding: '8px 12px',
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
                    {createVersionError}
                  </div>
                )}
              </div>
              <div className="modal-footer" style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <button
                  type="button"
                  onClick={() => setShowNewVersionModal(false)}
                  className="btn btn-secondary"
                  disabled={creatingVersion}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creatingVersion}
                  className="btn btn-primary"
                >
                  {creatingVersion ? 'Creating Version…' : 'Create Version'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Delete Version Confirmation Modal */}
      {showDeleteModal && (
        <div
          className="modal-overlay modal-backdrop"
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(15, 23, 42, 0.45)',
            display: 'grid',
            placeItems: 'center',
            zIndex: 1000,
            padding: 16,
          }}
          onClick={() => !deletingVersion && setShowDeleteModal(false)}
        >
          <div
            className="panel"
            style={{
              maxWidth: 460,
              width: '100%',
              padding: 24,
              boxShadow: 'var(--shadow-lg)',
              backgroundColor: 'var(--color-paper)',
              borderRadius: 'var(--radius-card)',
            }}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="delete-modal-title"
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--color-st-failed)' }}>
                <Trash2 size={18} />
                <h3 id="delete-modal-title" style={{ fontSize: 16, fontWeight: 700, margin: 0, color: 'var(--color-harbor)' }}>
                  {versionsToDelete.length === 1
                    ? `Delete Version v${versionsToDelete[0]}`
                    : `Delete ${versionsToDelete.length} Versions`}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => !deletingVersion && setShowDeleteModal(false)}
                className="btn btn-ghost"
                style={{ padding: 4 }}
                aria-label="Close dialog"
                disabled={deletingVersion}
              >
                <X size={16} />
              </button>
            </div>

            <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.5, marginBottom: 16 }}>
              {versionsToDelete.length === 1
                ? `Are you sure you want to permanently delete version v${versionsToDelete[0]}? This action cannot be undone.`
                : `Are you sure you want to permanently delete the following ${versionsToDelete.length} versions: ${versionsToDelete.map(v => `v${v}`).join(', ')}? This action cannot be undone.`}
            </p>

            {versionsToDelete.some(v => template?.versions.find(tv => tv.versionNumber === v)?.status === 'PUBLISHED') && (
              <div
                style={{
                  marginBottom: 16,
                  padding: '10px 12px',
                  backgroundColor: 'var(--color-st-scheduled-bg)',
                  border: '1px solid var(--color-st-scheduled)',
                  borderRadius: 'var(--radius-control)',
                  fontSize: 12,
                  color: 'var(--color-st-scheduled)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <AlertCircle size={14} style={{ flexShrink: 0 }} />
                <span>
                  <strong>Warning:</strong> One of the selected versions is currently <strong>PUBLISHED</strong>. Deleting it will leave this template without an active published version.
                </span>
              </div>
            )}

            {deleteVersionError && (
              <div
                style={{
                  marginBottom: 16,
                  padding: '10px 12px',
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
                <AlertCircle size={14} style={{ flexShrink: 0 }} />
                <span>{deleteVersionError}</span>
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                onClick={() => setShowDeleteModal(false)}
                className="btn btn-secondary"
                disabled={deletingVersion}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDeleteSubmit}
                disabled={deletingVersion}
                className="btn btn-primary"
                style={{
                  backgroundColor: 'var(--color-st-failed)',
                  borderColor: 'var(--color-st-failed)',
                  color: '#fff',
                }}
              >
                {deletingVersion ? 'Deleting…' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}