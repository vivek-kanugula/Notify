import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  listTemplates,
  createTemplate,
  deleteTemplate,
  type Template,
  type TemplateChannel,
  type TemplateContentType,
  type CreateTemplateRequest,
} from '../api/templates';
import { getChannelIcon } from '../components/FilterBar';
import { Plus, Trash2, ExternalLink, FileText, AlertCircle, X } from 'lucide-react';

export default function TemplatesPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const isSuspended = user?.tenantStatus === 'SUSPENDED';

  const [templates, setTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New Template Modal state
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [name, setName] = useState('');
  const [channel, setChannel] = useState<TemplateChannel>('EMAIL');
  const [description, setDescription] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [contentType, setContentType] = useState<TemplateContentType>('TEXT');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const fetchTemplates = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await listTemplates();
      setTemplates(data);
    } catch (err: any) {
      setError(err.message || 'Failed to load templates');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTemplates();
  }, []);

  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) {
      setFormError('Template name is required');
      return;
    }
    if (!body.trim()) {
      setFormError('Initial body content is required');
      return;
    }

    try {
      setSubmitting(true);
      setFormError(null);

      const request: CreateTemplateRequest = {
        name: name.trim(),
        channel,
        description: description.trim() || undefined,
        subject: channel === 'EMAIL' ? subject.trim() || undefined : undefined,
        body: body.trim(),
        contentType,
      };

      const created = await createTemplate(request);
      setShowCreateModal(false);
      setName('');
      setDescription('');
      setSubject('');
      setBody('');
      navigate(`/app/templates/${created.id}`);
    } catch (err: any) {
      setFormError(err.message || 'Failed to create template');
    } finally {
      setSubmitting(false);
    }
  };

  // Delete confirmation state
  const [templateToDelete, setTemplateToDelete] = useState<{ id: string; name: string } | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const handleDelete = (id: string, templateName: string) => {
    setTemplateToDelete({ id, name: templateName });
    setDeleteError(null);
  };

  const confirmDelete = async () => {
    if (!templateToDelete) return;
    try {
      setDeleting(true);
      setDeleteError(null);
      await deleteTemplate(templateToDelete.id);
      setTemplateToDelete(null);
      fetchTemplates();
    } catch (err: any) {
      setDeleteError(err.message || 'Failed to delete template');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="page-container" style={{ maxWidth: 1200 }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 12, marginBottom: 20 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)', marginBottom: 4 }}>
            Templates & Versioning
          </h1>
          <p style={{ color: 'var(--color-ink-2)', fontSize: 13 }}>
            Reusable notification content parameterized with <code className="mono">{'{{variables}}'}</code> and immutable version controls.
          </p>
        </div>

        <button
          type="button"
          onClick={() => {
            setShowCreateModal(true);
            setFormError(null);
          }}
          disabled={isSuspended}
          className="btn btn-primary"
        >
          <Plus size={15} />
          New Template
        </button>
      </div>

      {/* Main Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-3)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
            Loading templates…
          </div>
        ) : error ? (
          <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-st-failed)' }}>
            <AlertCircle size={24} style={{ margin: '0 auto 8px', display: 'block' }} />
            <p>{error}</p>
          </div>
        ) : templates.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <FileText size={36} style={{ margin: '0 auto 12px', opacity: 0.35, color: 'var(--color-ink-3)' }} />
            <h3 style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 6 }}>
              No templates created yet
            </h3>
            <p style={{ fontSize: 13, marginBottom: 16, color: 'var(--color-ink-3)' }}>
              Templates let you reuse message layouts and inject dynamic variable payloads.
            </p>
            <button
              type="button"
              onClick={() => setShowCreateModal(true)}
              disabled={isSuspended}
              className="btn btn-secondary btn-sm"
            >
              Create first template
            </button>
          </div>
        ) : (
          <div className="table-container" style={{ border: 'none', borderRadius: 0 }}>
            <table className="dense-table">
              <thead>
                <tr>
                  <th>Name & Description</th>
                  <th style={{ width: 120 }}>Channel</th>
                  <th style={{ width: 160 }}>Active Version</th>
                  <th style={{ width: 140 }}>Updated</th>
                  <th style={{ width: 140 }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {templates.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <div style={{ fontWeight: 600, color: 'var(--color-harbor)', fontSize: 13 }}>{t.name}</div>
                      {t.description && (
                        <div style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>{t.description}</div>
                      )}
                    </td>
                    <td>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontWeight: 600, fontSize: 12 }}>
                        {getChannelIcon(t.channel as any, 13)}
                        <span>{t.channel}</span>
                      </span>
                    </td>
                    <td>
                      {t.currentVersion ? (
                        <span
                          className={`status-chip ${t.currentVersion.status === 'PUBLISHED' ? 'status-delivered' : 'status-queued'}`}
                          style={{ fontSize: 11, padding: '2px 8px' }}
                        >
                          v{t.currentVersion.versionNumber} ({t.currentVersion.status})
                        </span>
                      ) : (
                        <span style={{ color: 'var(--color-ink-3)', fontSize: 12 }}>No version</span>
                      )}
                    </td>
                    <td>
                      <span className="mono tabular-nums" style={{ fontSize: 12, color: 'var(--color-ink-3)' }}>
                        {new Date(t.updatedAt || t.createdAt).toLocaleDateString()}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Link
                          to={`/app/templates/${t.id}`}
                          className="btn btn-secondary btn-sm"
                          style={{ padding: '2px 8px', fontSize: 11, textDecoration: 'none' }}
                        >
                          Edit <ExternalLink size={11} />
                        </Link>
                        <button
                          type="button"
                          onClick={() => handleDelete(t.id, t.name)}
                          disabled={isSuspended}
                          title="Delete template"
                          className="btn btn-ghost btn-icon"
                          style={{ color: 'var(--color-st-failed)' }}
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Create Template Modal */}
      {showCreateModal && (
        <div className="modal-overlay">
          <div className="modal-card" style={{ maxWidth: 520 }}>
            <div className="modal-header">
              <h3 style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-harbor)' }}>
                New Notification Template
              </h3>
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="btn btn-ghost btn-icon"
              >
                <X size={15} />
              </button>
            </div>

            {formError && (
              <div
                style={{
                  padding: '8px 12px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  border: '1px solid var(--color-st-failed-border)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                  marginBottom: 14,
                }}
              >
                {formError}
              </div>
            )}

            <form onSubmit={handleCreateSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div className="form-group">
                <label>Template Name:</label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. order-shipped-notification"
                  required
                />
              </div>

              <div className="form-group">
                <label>Channel:</label>
                <select
                  value={channel}
                  onChange={(e) => setChannel(e.target.value as TemplateChannel)}
                >
                  <option value="EMAIL">Email</option>
                  <option value="WEBHOOK">Webhook</option>
                  <option value="IN_APP">In-App</option>
                </select>
              </div>

              {channel === 'EMAIL' && (
                <div className="form-group">
                  <label>Subject Line (supports {'{{variables}}'}):</label>
                  <input
                    type="text"
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    placeholder="Your order {{order_id}} has shipped!"
                  />
                </div>
              )}

              <div className="form-group">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <label>Initial Body Content:</label>
                  <select
                    value={contentType}
                    onChange={(e) => setContentType(e.target.value as TemplateContentType)}
                    style={{ fontSize: 11, padding: '2px 6px', maxWidth: 110 }}
                  >
                    <option value="TEXT">Plain Text</option>
                    <option value="HTML">HTML</option>
                  </select>
                </div>
                <textarea
                  rows={5}
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  placeholder="Hello {{customer_name}}, thank you for your order..."
                  className="mono"
                  required
                />
              </div>

              <div className="form-group">
                <label>Description (Optional):</label>
                <input
                  type="text"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Transactional notification sent upon parcel dispatch"
                />
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
                  {submitting ? 'Creating…' : 'Create Template'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Delete Template Confirmation Modal */}
      {templateToDelete && (
        <div className="modal-overlay">
          <div className="modal-card" style={{ maxWidth: 440 }}>
            <h3 style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-st-failed)', marginBottom: 8 }}>
              Delete Template?
            </h3>
            <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.5, marginBottom: 16 }}>
              Are you sure you want to delete <strong>{templateToDelete.name}</strong>? All versions and draft content will be permanently removed.
            </p>
            {deleteError && (
              <div style={{ padding: '8px 12px', backgroundColor: 'var(--color-st-failed-bg)', border: '1px solid var(--color-st-failed-border)', borderRadius: 'var(--radius-control)', color: 'var(--color-st-failed)', fontSize: 12, marginBottom: 16 }}>
                {deleteError}
              </div>
            )}
            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setTemplateToDelete(null)}
                className="btn btn-secondary"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={confirmDelete}
                disabled={deleting}
                className="btn btn-danger"
              >
                {deleting ? 'Deleting…' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
