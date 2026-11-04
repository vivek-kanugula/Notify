import React, { useState, useEffect, useRef, useMemo } from 'react';
import { useSearchParams, Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  listNotifications,
  getNotification,
  getNotificationHistory,
  createNotification,
  cancelNotification,
  type NotificationSummary,
  type NotificationDetail,
  type NotificationHistoryEntry,
  type Channel,
  type NotificationStatus,
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
import LifecycleRail from '../components/LifecycleRail';
import SandboxedIframe from '../components/SandboxedIframe';
import FilterBar, { getChannelIcon } from '../components/FilterBar';
import {
  parseFilterState,
  serializeFilterState,
  hasActiveFilters,
  filterNotificationsClientSide,
  FILTER_CHANNELS,
} from '../components/notificationsFilterLogic';
import {
  formatRelativeTime,
  isTerminalStatus,
} from '../components/lifecycleRailLogic';
import {
  ExternalLink,
  Plus,
  AlertCircle,
  X,
} from 'lucide-react';

export default function NotificationsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // Filters state from URL
  const filterState = useMemo(() => parseFilterState(searchParams), [searchParams]);
  const isFiltered = useMemo(() => hasActiveFilters(filterState), [filterState]);

  // Notifications data state
  const [notifications, setNotifications] = useState<NotificationSummary[]>([]);
  const [totalElements, setTotalElements] = useState<number>(0);
  const [totalPages, setTotalPages] = useState<number>(1);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Status transition flash animation tracker (row ID -> previous status)
  const prevStatusesRef = useRef<Map<string, NotificationStatus>>(new Map());
  const [flashingRowIds, setFlashingRowIds] = useState<Set<string>>(new Set());

  // Modals & Drawers state
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [detailItem, setDetailItem] = useState<NotificationDetail | null>(null);
  const [detailHistory, setDetailHistory] = useState<NotificationHistoryEntry[]>([]);

  // Cancellation modal state
  const [cancelTargetId, setCancelTargetId] = useState<string | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  // Quick Create form state (modal)
  const [formChannel, setFormChannel] = useState<Channel>('EMAIL');
  const [formRecipient, setFormRecipient] = useState('');
  const [contentMode, setContentMode] = useState<'custom' | 'template'>('custom');
  const [formSubject, setFormSubject] = useState('');
  const [formBody, setFormBody] = useState('');
  const [formIdempotencyKey, setFormIdempotencyKey] = useState('');
  const [formScheduledAt, setFormScheduledAt] = useState('');

  // Template state
  const [templates, setTemplates] = useState<Template[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string>('');
  const [templateDetail, setTemplateDetail] = useState<TemplateDetail | null>(null);
  const [selectedVersionNumber, setSelectedVersionNumber] = useState<number | ''>('');
  const [activeVersion, setActiveVersion] = useState<TemplateVersion | null>(null);
  const [templateVariables, setTemplateVariables] = useState<Record<string, string>>({});

  const [submitting, setSubmitting] = useState(false);
  const [createFeedback, setCreateFeedback] = useState<{ msg: string; isReplay: boolean; id: string } | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);

  const isSuspended = user?.tenantStatus === 'SUSPENDED';

  // Fetch list from backend
  const fetchList = async (isBackgroundPoll = false) => {
    try {
      if (!isBackgroundPoll) setLoading(true);
      setError(null);

      const data = await listNotifications({
        channel: filterState.channel || undefined,
        status: filterState.status || undefined,
        page: filterState.page,
        size: filterState.size,
      });

      const newContent = data.content || [];

      // Check for status changes to trigger visual flash
      const nextFlashing = new Set<string>();
      for (const item of newContent) {
        const prevStatus = prevStatusesRef.current.get(item.id);
        if (prevStatus && prevStatus !== item.status) {
          nextFlashing.add(item.id);
        }
        prevStatusesRef.current.set(item.id, item.status);
      }

      if (nextFlashing.size > 0) {
        setFlashingRowIds(nextFlashing);
        setTimeout(() => {
          setFlashingRowIds(new Set());
        }, 1500);
      }

      setNotifications(newContent);
      setTotalElements(data.totalElements || newContent.length);
      setTotalPages(data.totalPages || 1);
    } catch (err: any) {
      if (!isBackgroundPoll) {
        setError(err.message || 'Failed to load notifications');
      }
    } finally {
      if (!isBackgroundPoll) setLoading(false);
    }
  };

  // Open modal if ?create=true in URL
  useEffect(() => {
    if (searchParams.get('create') === 'true') {
      setShowCreateModal(true);
      const nextParams = new URLSearchParams(searchParams);
      nextParams.delete('create');
      setSearchParams(nextParams, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  // Load templates when modal opens or channel changes
  useEffect(() => {
    if (!showCreateModal) return;
    let mounted = true;
    setTemplatesLoading(true);
    listTemplates()
      .then((data) => {
        if (!mounted) return;
        const matched = data.filter((t) => t.channel === formChannel);
        setTemplates(matched);
        if (selectedTemplateId && !matched.some((t) => t.id === selectedTemplateId)) {
          setSelectedTemplateId('');
          setTemplateDetail(null);
          setActiveVersion(null);
          setSelectedVersionNumber('');
          setTemplateVariables({});
        }
      })
      .catch(() => {
        if (mounted) setTemplates([]);
      })
      .finally(() => {
        if (mounted) setTemplatesLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, [showCreateModal, formChannel, selectedTemplateId]);

  // Load template details when selected template changes
  useEffect(() => {
    if (!selectedTemplateId) {
      setTemplateDetail(null);
      setActiveVersion(null);
      setSelectedVersionNumber('');
      setTemplateVariables({});
      return;
    }

    let mounted = true;
    getTemplate(selectedTemplateId)
      .then((detail) => {
        if (!mounted) return;
        setTemplateDetail(detail);
        const published = detail.versions.find((v) => v.status === 'PUBLISHED');
        const defaultVer = published || detail.versions[0];
        if (defaultVer) {
          setSelectedVersionNumber(defaultVer.versionNumber);
          setActiveVersion(defaultVer);
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

  // Preview content for template mode
  const previewSubject = activeVersion
    ? interpolateTemplateVariables(activeVersion.subject || '', templateVariables)
    : '';
  const previewBody = activeVersion
    ? interpolateTemplateVariables(activeVersion.body || '', templateVariables)
    : '';

  // Initial fetch and on filter/page change
  useEffect(() => {
    fetchList();
  }, [filterState.channel, filterState.status, filterState.page, filterState.size]);

  // Smart polling: poll every 2s in realtime if any non-terminal rows exist on active page
  const hasActiveNonTerminal = useMemo(() => {
    return notifications.some((n) => !isTerminalStatus(n.status));
  }, [notifications]);

  useEffect(() => {
    if (!hasActiveNonTerminal) return;

    const timer = setInterval(() => {
      fetchList(true);
    }, 2000);

    return () => clearInterval(timer);
  }, [hasActiveNonTerminal, filterState]);

  // URL search params updater helpers
  const handleChannelChange = (channel: Channel | '') => {
    const nextState = { ...filterState, channel, page: 0 };
    setSearchParams(serializeFilterState(nextState));
  };

  const handleStatusChange = (status: NotificationStatus | '') => {
    const nextState = { ...filterState, status, page: 0 };
    setSearchParams(serializeFilterState(nextState));
  };

  const handleSearchChange = (search: string) => {
    const nextState = { ...filterState, search };
    setSearchParams(serializeFilterState(nextState));
  };

  const handleClearFilters = () => {
    setSearchParams({});
  };

  const handlePageChange = (newPage: number) => {
    const nextState = { ...filterState, page: newPage };
    setSearchParams(serializeFilterState(nextState));
  };

  // Client-side text filter on current page results (recipient, subject, ID)
  const displayedNotifications = useMemo(() => {
    return filterNotificationsClientSide(notifications, filterState.search);
  }, [notifications, filterState.search]);

  // Drawer detail fetcher
  const handleOpenDetail = async (id: string) => {
    try {
      const [detailData, historyData] = await Promise.all([
        getNotification(id),
        getNotificationHistory(id),
      ]);
      setDetailItem(detailData);
      setDetailHistory(historyData);
    } catch (err: any) {
      setError(`Failed to load details for notification: ${err.message || 'Unknown error'}`);
    }
  };

  // Quick Dispatch Form Submit
  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateError(null);
    setCreateFeedback(null);

    const trimmedRecipient = formRecipient.trim();
    if (!trimmedRecipient) {
      setCreateError('Recipient is required');
      return;
    }

    if (contentMode === 'custom') {
      if (!formBody.trim()) {
        setCreateError('Message body cannot be empty');
        return;
      }
    } else {
      if (!selectedTemplateId) {
        setCreateError('Please select a template');
        return;
      }
    }

    try {
      setSubmitting(true);

      const payload: CreateNotificationRequest = {
        channel: formChannel,
        recipient: trimmedRecipient,
        subject: formChannel === 'EMAIL' ? (contentMode === 'custom' ? formSubject.trim() || undefined : undefined) : undefined,
        body: contentMode === 'custom' ? formBody.trim() : undefined,
        templateId: contentMode === 'template' ? selectedTemplateId : undefined,
        templateVersion: contentMode === 'template' && selectedVersionNumber !== '' ? Number(selectedVersionNumber) : undefined,
        templateVariables: contentMode === 'template' && Object.keys(templateVariables).length > 0 ? templateVariables : undefined,
        idempotencyKey: formIdempotencyKey.trim() || undefined,
        scheduledAt: formScheduledAt ? new Date(formScheduledAt).toISOString() : undefined,
      };

      const res = await createNotification(payload, formIdempotencyKey.trim() || undefined);

      // Clear inputs
      setFormRecipient('');
      setFormSubject('');
      setFormBody('');
      setFormIdempotencyKey('');
      setFormScheduledAt('');
      setSelectedTemplateId('');
      setTemplateDetail(null);
      setActiveVersion(null);
      setTemplateVariables({});
      setShowCreateModal(false);

      // Navigate straight to the created notification's detail page
      // where the lifecycle rail updates in real time!
      navigate(`/app/notifications/${res.data.id}`);
    } catch (err: any) {
      setCreateError(err.message || 'Failed to accept notification');
    } finally {
      setSubmitting(false);
    }
  };

  // Cancel Handler
  const handleCancelSubmit = async () => {
    if (!cancelTargetId) return;
    try {
      setCancelling(true);
      setCancelError(null);
      await cancelNotification(cancelTargetId, cancelReason.trim() || undefined);
      setCancelTargetId(null);
      setCancelReason('');
      if (detailItem && detailItem.id === cancelTargetId) {
        await handleOpenDetail(cancelTargetId);
      }
      await fetchList();
    } catch (err: any) {
      const msg = err.message || '';
      if (msg.includes('409') || msg.includes('INVALID_STATE_TRANSITION')) {
        setCancelError('This notification already started processing and can no longer be cancelled.');
        fetchList();
      } else {
        setCancelError(`Cancellation failed: ${msg}`);
      }
    } finally {
      setCancelling(false);
    }
  };

  const hasQueued = notifications.some((n) => n.status === 'QUEUED');
  const hasScheduled = notifications.some((n) => n.status === 'SCHEDULED');

  return (
    <div className="page-container" style={{ maxWidth: 1280 }}>
      {/* Page Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 20,
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)', marginBottom: 4 }}>
            Notifications
          </h1>
          <p style={{ color: 'var(--color-ink-2)', fontSize: 13 }}>
            Inbound notification acceptance, idempotency verification, and delivery audit trail.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button
            type="button"
            onClick={() => {
              setShowCreateModal(true);
              setCreateFeedback(null);
              setCreateError(null);
            }}
            disabled={isSuspended}
            className="btn btn-primary"
            style={{ fontSize: 13 }}
          >
            <Plus size={15} />
            Send Notification
          </button>
        </div>
      </div>

      {/* Status Notice Banners */}
      {hasQueued && (
        <div
          className="panel"
          style={{
            backgroundColor: 'var(--color-st-processing-bg)',
            borderColor: 'var(--color-st-processing-border)',
            padding: '10px 16px',
            marginBottom: 12,
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <span className="status-chip status-processing">Dispatch Pending</span>
          <span style={{ fontSize: 13, color: 'var(--color-harbor)' }}>
            Transactional outbox rows are committed for <strong>QUEUED</strong> notifications and asynchronously dispatched to workers via Kafka.
          </span>
        </div>
      )}

      {hasScheduled && (
        <div
          className="panel"
          style={{
            backgroundColor: 'var(--color-st-scheduled-bg)',
            borderColor: 'var(--color-st-scheduled-border)',
            padding: '10px 16px',
            marginBottom: 16,
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <span className="status-chip status-scheduled">Scheduled Dispatch</span>
          <span style={{ fontSize: 13, color: 'var(--color-harbor)' }}>
            Future dispatches are durably stored in PostgreSQL and automatically scheduled for future dispatch via Quartz.
          </span>
        </div>
      )}

      {/* Unified Filter Bar */}
      <FilterBar
        channel={filterState.channel}
        status={filterState.status}
        search={filterState.search}
        onChannelChange={handleChannelChange}
        onStatusChange={handleStatusChange}
        onSearchChange={handleSearchChange}
        onClearFilters={handleClearFilters}
        onRefresh={() => fetchList()}
        isFiltered={isFiltered}
      />

      {/* Dense Notifications Table */}
      <div className="panel" style={{ padding: 0, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-3)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
            Loading notifications…
          </div>
        ) : error ? (
          <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-st-failed)' }}>
            <AlertCircle size={24} style={{ margin: '0 auto 8px', display: 'block' }} />
            <div style={{ fontWeight: 600, marginBottom: 8 }}>Couldn't load notifications.</div>
            <p style={{ fontSize: 13, marginBottom: 14 }}>{error}</p>
            <button
              type="button"
              onClick={() => fetchList()}
              className="btn btn-secondary btn-sm"
            >
              Try again
            </button>
          </div>
        ) : displayedNotifications.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-2)' }}>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-harbor)', marginBottom: 6 }}>
              {isFiltered ? 'No notifications match these filters.' : 'No notifications found'}
            </div>
            <p style={{ fontSize: 13, marginBottom: 16, color: 'var(--color-ink-3)' }}>
              {isFiltered
                ? 'Try adjusting or clearing your active channel, status, or search filters.'
                : 'Accept and enqueue your first notification dispatch using the button above or curl API.'}
            </p>
            {isFiltered && (
              <button
                type="button"
                onClick={handleClearFilters}
                className="btn btn-secondary btn-sm"
              >
                Clear filters
              </button>
            )}
          </div>
        ) : (
          <div className="table-container" style={{ border: 'none', borderRadius: 0 }}>
            <table className="dense-table">
              <thead>
                <tr>
                  <th style={{ width: 180 }}>Status & Rail</th>
                  <th style={{ width: 100 }}>Channel</th>
                  <th>Recipient</th>
                  <th>Subject</th>
                  <th style={{ width: 150 }}>Created</th>
                  <th style={{ width: 130 }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {displayedNotifications.map((n) => {
                  const isFlashing = flashingRowIds.has(n.id);
                  const isEligibleCancel = n.status === 'SCHEDULED' || n.status === 'QUEUED';

                  return (
                    <tr
                      key={n.id}
                      style={{
                        backgroundColor: isFlashing ? 'var(--color-signal-subtle)' : undefined,
                        transition: 'background-color 0.4s ease-out',
                      }}
                    >
                      {/* Status + Compact Lifecycle Rail (96x6px) */}
                      <td>
                        <LifecycleRail
                          status={n.status}
                          variant="compact"
                        />
                      </td>

                      {/* Channel */}
                      <td>
                        <span
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 5,
                            fontWeight: 600,
                            fontSize: 12,
                          }}
                        >
                          {getChannelIcon(n.channel, 13)}
                          <span>{n.channel}</span>
                        </span>
                      </td>

                      {/* Recipient */}
                      <td>
                        <span
                          className="mono"
                          style={{
                            fontSize: 12,
                            display: 'block',
                            maxWidth: 220,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            color: 'var(--color-harbor)',
                          }}
                          title={n.recipient}
                        >
                          {n.recipient}
                        </span>
                      </td>

                      {/* Subject */}
                      <td>
                        <span
                          style={{
                            display: 'block',
                            maxWidth: 240,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            color: n.subject ? 'var(--color-harbor)' : 'var(--color-ink-3)',
                          }}
                          title={n.subject || undefined}
                        >
                          {n.subject || '—'}
                        </span>
                      </td>

                      {/* Created */}
                      <td>
                        <span
                          className="mono tabular-nums"
                          style={{
                            fontSize: 12,
                            color: 'var(--color-ink-3)',
                          }}
                          title={`${new Date(n.createdAt).toLocaleString()} (${new Date(n.createdAt).toUTCString()})`}
                        >
                          {formatRelativeTime(n.createdAt) || new Date(n.createdAt).toLocaleDateString()}
                        </span>
                      </td>

                      {/* Row Actions */}
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <button
                            type="button"
                            onClick={() => handleOpenDetail(n.id)}
                            className="btn btn-secondary btn-sm"
                            style={{ padding: '2px 8px', fontSize: 11 }}
                          >
                            Detail
                          </button>

                          <Link
                            to={`/app/notifications/${n.id}`}
                            title="Open full page view"
                            className="btn btn-ghost btn-sm"
                            style={{
                              padding: '4px',
                              color: 'var(--color-signal)',
                            }}
                          >
                            <ExternalLink size={12} />
                          </Link>

                          {isEligibleCancel && (
                            <button
                              type="button"
                              onClick={() => {
                                setCancelTargetId(n.id);
                                setCancelError(null);
                              }}
                              className="btn btn-ghost btn-sm"
                              style={{
                                padding: '2px 6px',
                                fontSize: 11,
                                color: 'var(--color-st-failed)',
                              }}
                            >
                              Cancel
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Bar */}
        {!loading && displayedNotifications.length > 0 && (
          <div
            style={{
              padding: '10px 16px',
              borderTop: '1px solid var(--color-rail)',
              backgroundColor: 'var(--color-subtle)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              fontSize: 12,
              color: 'var(--color-ink-2)',
            }}
          >
            <span>
              Showing {displayedNotifications.length} of {totalElements} notifications
            </span>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <button
                type="button"
                disabled={filterState.page <= 0}
                onClick={() => handlePageChange(filterState.page - 1)}
                className="btn btn-secondary btn-sm"
              >
                Previous
              </button>

              <span className="mono tabular-nums" style={{ fontWeight: 600, color: 'var(--color-harbor)' }}>
                Page {filterState.page + 1} of {totalPages || 1}
              </span>

              <button
                type="button"
                disabled={filterState.page + 1 >= totalPages}
                onClick={() => handlePageChange(filterState.page + 1)}
                className="btn btn-secondary btn-sm"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Quick Dispatch Modal Drawer */}
      {showCreateModal && (
        <div className="modal-overlay">
          <div className="modal-card" style={{ maxWidth: 540 }}>
            <div className="modal-header">
              <h2 style={{ fontSize: 17, fontWeight: 700, color: 'var(--color-harbor)' }}>
                Dispatch Notification
              </h2>
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="btn btn-ghost btn-icon"
                aria-label="Close modal"
              >
                <X size={16} />
              </button>
            </div>

            {createFeedback && (
              <div
                style={{
                  padding: '10px 14px',
                  borderRadius: 'var(--radius-control)',
                  backgroundColor: createFeedback.isReplay ? 'var(--color-st-retrying-bg)' : 'var(--color-st-delivered-bg)',
                  borderColor: createFeedback.isReplay ? 'var(--color-st-retrying-border)' : 'var(--color-st-delivered-border)',
                  border: '1px solid',
                  color: createFeedback.isReplay ? 'var(--color-st-retrying)' : 'var(--color-st-delivered)',
                  marginBottom: 16,
                  fontSize: 13,
                }}
              >
                <strong>{createFeedback.isReplay ? 'Matching Idempotent Replay (202):' : 'Success:'}</strong> {createFeedback.msg}
              </div>
            )}

            {createError && (
              <div
                style={{
                  padding: '10px 14px',
                  borderRadius: 'var(--radius-control)',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  borderColor: 'var(--color-st-failed-border)',
                  border: '1px solid',
                  color: 'var(--color-st-failed)',
                  marginBottom: 16,
                  fontSize: 13,
                }}
              >
                {createError}
              </div>
            )}

            <form onSubmit={handleCreateSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              {/* Channel Selector */}
              <div className="form-group">
                <label>Channel</label>
                <div style={{ display: 'flex', gap: 8 }}>
                  {FILTER_CHANNELS.map((ch) => (
                    <button
                      key={ch}
                      type="button"
                      onClick={() => setFormChannel(ch)}
                      style={{
                        flex: 1,
                        padding: '8px 10px',
                        fontSize: 12,
                        fontWeight: formChannel === ch ? 600 : 500,
                        backgroundColor: formChannel === ch ? 'var(--color-signal-subtle)' : 'var(--color-paper)',
                        color: formChannel === ch ? 'var(--color-signal)' : 'var(--color-harbor)',
                        border: formChannel === ch ? '1.5px solid var(--color-signal)' : '1px solid var(--color-rail)',
                        borderRadius: 'var(--radius-control)',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: 6,
                        transition: 'all 0.15s ease',
                      }}
                    >
                      {getChannelIcon(ch, 13)}
                      <span>{ch}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Recipient */}
              <div className="form-group">
                <label>Recipient</label>
                <input
                  type={formChannel === 'EMAIL' ? 'email' : 'text'}
                  value={formRecipient}
                  onChange={(e) => setFormRecipient(e.target.value)}
                  placeholder="user@example.com"
                  className="mono"
                  required
                />
              </div>

              {/* Message Content Mode Toggle */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 4, marginBottom: 2 }}>
                <label style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-harbor)', margin: 0 }}>
                  Message Content
                </label>
                <div
                  style={{
                    display: 'inline-flex',
                    padding: 2,
                    backgroundColor: 'var(--color-chalk)',
                    borderRadius: 'var(--radius-control)',
                    border: '1px solid var(--color-rail)',
                  }}
                >
                  <button
                    type="button"
                    onClick={() => setContentMode('custom')}
                    style={{
                      padding: '4px 12px',
                      fontSize: 12,
                      fontWeight: contentMode === 'custom' ? 600 : 500,
                      backgroundColor: contentMode === 'custom' ? 'var(--color-paper)' : 'transparent',
                      color: contentMode === 'custom' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                      border: 'none',
                      borderRadius: 'calc(var(--radius-control) - 2px)',
                      boxShadow: contentMode === 'custom' ? 'var(--shadow-sm)' : 'none',
                      cursor: 'pointer',
                    }}
                  >
                    Custom Message
                  </button>
                  <button
                    type="button"
                    onClick={() => setContentMode('template')}
                    style={{
                      padding: '4px 12px',
                      fontSize: 12,
                      fontWeight: contentMode === 'template' ? 600 : 500,
                      backgroundColor: contentMode === 'template' ? 'var(--color-paper)' : 'transparent',
                      color: contentMode === 'template' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                      border: 'none',
                      borderRadius: 'calc(var(--radius-control) - 2px)',
                      boxShadow: contentMode === 'template' ? 'var(--shadow-sm)' : 'none',
                      cursor: 'pointer',
                    }}
                  >
                    Use Template
                  </button>
                </div>
              </div>

              {/* Custom Message Mode Inputs */}
              {contentMode === 'custom' && (
                <>
                  {formChannel === 'EMAIL' && (
                    <div className="form-group">
                      <label>Subject</label>
                      <input
                        type="text"
                        value={formSubject}
                        onChange={(e) => setFormSubject(e.target.value)}
                        placeholder="Notification Subject"
                      />
                    </div>
                  )}

                  <div className="form-group">
                    <label>Body Content</label>
                    <textarea
                      rows={4}
                      value={formBody}
                      onChange={(e) => setFormBody(e.target.value)}
                      placeholder="Notification content / payload..."
                      style={{
                        fontFamily: formChannel === 'EMAIL' ? 'var(--font-sans)' : 'var(--font-mono)',
                      }}
                      required
                    />
                  </div>
                </>
              )}

              {/* Template Mode Inputs */}
              {contentMode === 'template' && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <div className="form-group">
                    <label>Select Template ({formChannel})</label>
                    {templatesLoading ? (
                      <div style={{ fontSize: 12, color: 'var(--color-ink-2)', padding: '6px 0' }}>Loading templates…</div>
                    ) : templates.length === 0 ? (
                      <div style={{ fontSize: 12, color: 'var(--color-ink-2)', padding: '10px 12px', backgroundColor: 'var(--color-chalk)', borderRadius: 'var(--radius-control)', border: '1px solid var(--color-rail)' }}>
                        No templates found for <strong>{formChannel}</strong>. You can switch to Custom Message or <Link to="/app/templates" style={{ color: 'var(--color-signal)' }}>create a template</Link>.
                      </div>
                    ) : (
                      <select
                        value={selectedTemplateId}
                        onChange={(e) => setSelectedTemplateId(e.target.value)}
                        style={{
                          width: '100%',
                          padding: '8px 10px',
                          fontSize: 13,
                          borderRadius: 'var(--radius-control)',
                          border: '1px solid var(--color-rail)',
                          backgroundColor: 'var(--color-paper)',
                          color: 'var(--color-harbor)',
                        }}
                        required
                      >
                        <option value="">-- Choose a template --</option>
                        {templates.map((tpl) => (
                          <option key={tpl.id} value={tpl.id}>
                            {tpl.name}{tpl.currentVersion?.versionNumber ? ` • v${tpl.currentVersion.versionNumber}` : ''}
                          </option>
                        ))}
                      </select>
                    )}
                  </div>

                  {templateDetail && templateDetail.versions && templateDetail.versions.length > 0 && (
                    <div className="form-group">
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                        <label style={{ fontSize: 12, fontWeight: 600, margin: 0 }}>Template Version</label>
                        {activeVersion && (
                          <span className={`status-chip status-${activeVersion.status.toLowerCase()}`} style={{ fontSize: 10, padding: '1px 6px' }}>
                            {activeVersion.status}
                          </span>
                        )}
                      </div>
                      <select
                        value={selectedVersionNumber}
                        onChange={(e) => handleVersionChange(Number(e.target.value))}
                        style={{
                          width: '100%',
                          padding: '6px 10px',
                          fontSize: 12,
                          borderRadius: 'var(--radius-control)',
                          border: '1px solid var(--color-rail)',
                          backgroundColor: 'var(--color-paper)',
                          color: 'var(--color-harbor)',
                        }}
                      >
                        {templateDetail.versions.map((ver) => (
                          <option key={ver.id} value={ver.versionNumber}>
                            v{ver.versionNumber} ({ver.status}){ver.subject ? ` — ${ver.subject}` : ''}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}

                  {/* Template Variables */}
                  {Object.keys(templateVariables).length > 0 && (
                    <div
                      style={{
                        padding: 12,
                        backgroundColor: 'var(--color-chalk)',
                        borderRadius: 'var(--radius-control)',
                        border: '1px solid var(--color-rail)',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 8,
                      }}
                    >
                      <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)', margin: 0 }}>
                        Template Variables ({Object.keys(templateVariables).length})
                      </label>
                      {Object.keys(templateVariables).map((vKey) => (
                        <div key={vKey} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <span
                            className="mono"
                            style={{
                              minWidth: 100,
                              fontSize: 11,
                              fontWeight: 600,
                              color: 'var(--color-signal)',
                              backgroundColor: 'var(--color-paper)',
                              padding: '4px 6px',
                              borderRadius: 4,
                              border: '1px solid var(--color-rail)',
                            }}
                          >
                            {`{{${vKey}}}`}
                          </span>
                          <input
                            type="text"
                            value={templateVariables[vKey]}
                            onChange={(e) => setTemplateVariables((prev) => ({ ...prev, [vKey]: e.target.value }))}
                            placeholder={`Value for ${vKey}`}
                            style={{ flex: 1, padding: '6px 10px', fontSize: 12 }}
                          />
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Rendered Preview */}
                  {activeVersion && (
                    <div
                      style={{
                        padding: 10,
                        backgroundColor: 'var(--color-paper-muted)',
                        borderRadius: 'var(--radius-control)',
                        border: '1px solid var(--color-rail)',
                        fontSize: 12,
                      }}
                    >
                      <div style={{ fontWeight: 600, color: 'var(--color-ink-2)', marginBottom: 4 }}>
                        Live Interpolation Preview:
                      </div>
                      {formChannel === 'EMAIL' && activeVersion.subject && (
                        <div style={{ marginBottom: 4 }}>
                          <strong>Subject:</strong> {previewSubject}
                        </div>
                      )}
                      <div style={{ whiteSpace: 'pre-wrap', color: 'var(--color-ink-1)', maxHeight: 100, overflowY: 'auto' }}>
                        {previewBody || <em>(Empty template body)</em>}
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Idempotency Key */}
              <div className="form-group">
                <label>Idempotency Key (Optional)</label>
                <input
                  type="text"
                  value={formIdempotencyKey}
                  onChange={(e) => setFormIdempotencyKey(e.target.value)}
                  placeholder="e.g. order-ack-40918"
                  className="mono"
                />
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="btn btn-secondary"
                >
                  Close
                </button>
                <button
                  type="submit"
                  disabled={submitting || isSuspended}
                  className="btn btn-primary"
                >
                  {submitting ? 'Enqueuing…' : 'Accept & Enqueue'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Inline Detail Drawer Panel */}
      {detailItem && (
        <div
          className="panel"
          style={{
            marginTop: 24,
            padding: 24,
            borderTop: '3px solid var(--color-signal)',
            backgroundColor: 'var(--color-paper)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                <span className={`status-chip status-${detailItem.status.toLowerCase()}`}>
                  {detailItem.status}
                </span>
                <span className="mono" style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-harbor)' }}>
                  {detailItem.recipient}
                </span>
                <Link
                  to={`/app/notifications/${detailItem.id}`}
                  style={{
                    fontSize: 12,
                    color: 'var(--color-signal)',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 3,
                    marginLeft: 6,
                    textDecoration: 'none',
                    fontWeight: 600,
                  }}
                >
                  Full Page <ExternalLink size={11} />
                </Link>
              </div>
              <p style={{ fontSize: 13, color: 'var(--color-ink-3)' }}>
                Notification ID: <code className="mono">{detailItem.id}</code>
              </p>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              {(detailItem.status === 'SCHEDULED' || detailItem.status === 'QUEUED') && (
                <button
                  type="button"
                  onClick={() => {
                    setCancelTargetId(detailItem.id);
                    setCancelError(null);
                  }}
                  className="btn btn-danger btn-sm"
                >
                  Cancel Notification
                </button>
              )}
              <button
                type="button"
                onClick={() => setDetailItem(null)}
                className="btn btn-ghost btn-icon"
                aria-label="Close detail panel"
              >
                <X size={16} />
              </button>
            </div>
          </div>

          {/* Lifecycle Rail Hero */}
          <LifecycleRail
            status={detailItem.status}
            scheduledAt={detailItem.scheduledAt}
            queuedAt={detailItem.queuedAt}
            processingStartedAt={detailItem.processingStartedAt}
            completedAt={detailItem.completedAt}
            createdAt={detailItem.createdAt}
            attemptCount={detailItem.attemptCount}
            maxAttempts={detailItem.maxAttempts}
            history={detailHistory}
            variant="full"
          />

          {/* Body Preview */}
          <div style={{ marginBottom: 20 }}>
            <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-harbor)', display: 'block', marginBottom: 6 }}>
              Payload / Body Preview:
            </span>
            {detailItem.channel === 'EMAIL' ? (
              <SandboxedIframe content={detailItem.body} minHeight={160} />
            ) : (
              <pre
                className="code-excerpt"
                style={{ padding: 12 }}
              >
                {detailItem.body}
              </pre>
            )}
          </div>

          {/* Immutable Audit Trail Section */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
              <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-harbor)' }}>
                Immutable Audit Trail
              </span>
              <span className="mono" style={{ fontSize: 11, color: 'var(--color-ink-3)' }}>
                {detailHistory.length} status transition{detailHistory.length === 1 ? '' : 's'}
              </span>
            </div>

            <div className="table-container">
              <table className="dense-table">
                <thead>
                  <tr>
                    <th>Transition</th>
                    <th>Actor</th>
                    <th>Reason</th>
                    <th>Time</th>
                  </tr>
                </thead>
                <tbody>
                  {detailHistory.map((h) => (
                    <tr key={h.id}>
                      <td>
                        <span className="mono">
                          {h.fromStatus ? `${h.fromStatus} → ` : ''}
                          <strong>{h.toStatus}</strong>
                        </span>
                      </td>
                      <td>
                        <span className="mono" style={{ fontWeight: 600 }}>{h.actor}</span>
                      </td>
                      <td style={{ color: h.reason ? 'inherit' : 'var(--color-ink-3)' }}>
                        {h.reason || '—'}
                      </td>
                      <td style={{ color: 'var(--color-ink-3)' }}>
                        <span className="mono tabular-nums">{new Date(h.occurredAt).toLocaleTimeString()}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Cancellation Confirmation Dialog */}
      {cancelTargetId && (
        <div className="modal-overlay">
          <div className="modal-card" style={{ maxWidth: 420 }}>
            <div className="modal-header">
              <h3 style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-harbor)' }}>
                Confirm Cancellation
              </h3>
              <button
                type="button"
                onClick={() => setCancelTargetId(null)}
                className="btn btn-ghost btn-icon"
              >
                <X size={15} />
              </button>
            </div>

            <p style={{ fontSize: 13, color: 'var(--color-ink-2)', lineHeight: 1.5, marginBottom: 14 }}>
              Cancel notification <code className="mono">{cancelTargetId}</code>? This action cannot be reversed.
            </p>

            {cancelError && (
              <div
                style={{
                  padding: '8px 12px',
                  backgroundColor: 'var(--color-st-failed-bg)',
                  border: '1px solid var(--color-st-failed-border)',
                  borderRadius: 'var(--radius-control)',
                  color: 'var(--color-st-failed)',
                  fontSize: 12,
                  marginBottom: 12,
                }}
              >
                {cancelError}
              </div>
            )}

            <div className="form-group" style={{ marginBottom: 16 }}>
              <label>Reason:</label>
              <input
                type="text"
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                placeholder="e.g. User opted out"
              />
            </div>

            <div className="modal-footer">
              <button
                type="button"
                onClick={() => setCancelTargetId(null)}
                className="btn btn-secondary"
              >
                Keep Active
              </button>
              <button
                type="button"
                onClick={handleCancelSubmit}
                disabled={cancelling}
                className="btn btn-danger"
              >
                {cancelling ? 'Cancelling…' : 'Confirm Cancel'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
