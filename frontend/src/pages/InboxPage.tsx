import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { listInAppMessages, markInAppMessageRead } from '../api/inapp';
import { InAppStream } from '../api/sseClient';
import { StreamState } from '../api/streamLoop';
import {
  InboxState,
  applyFirstPage,
  applyIncoming,
  applyMarkedRead,
  emptyInbox,
  highestSeq,
  isHigherSeq,
  mergePage,
} from './inboxState';
import {
  Inbox,
  Radio,
  RefreshCw,
  Search,
  Check,
  AlertCircle,
  ChevronDown,
} from 'lucide-react';

const PAGE_SIZE = 25;
const MAX_RECOVERY_PAGES = 20;
const MAX_RECOVERY_ATTEMPTS = 5;

export default function InboxPage() {
  const [recipientRef, setRecipientRef] = useState('demo-user');
  const [activeRecipient, setActiveRecipient] = useState('demo-user');
  const [inbox, setInbox] = useState<InboxState>(emptyInbox);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [streamState, setStreamState] = useState<StreamState>('idle');
  const [streamDetail, setStreamDetail] = useState<string | null>(null);
  const [resyncs, setResyncs] = useState(0);
  const [recoveryStalled, setRecoveryStalled] = useState(false);

  const streamRef = useRef<InAppStream | null>(null);
  const generationRef = useRef(0);

  const isCurrent = useCallback((generation: number) => generationRef.current === generation, []);

  const load = useCallback(async (recipient: string, generation: number): Promise<string | null> => {
    setLoading(true);
    setError(null);
    try {
      const page = await listInAppMessages(recipient, { size: PAGE_SIZE });
      if (!isCurrent(generation)) return null;
      setInbox(applyFirstPage(page.content, page.unread, page.nextCursor));
      return highestSeq(page.content);
    } catch (e) {
      if (!isCurrent(generation)) return null;
      setError(e instanceof Error ? e.message : 'The inbox could not be loaded');
      return null;
    } finally {
      if (isCurrent(generation)) setLoading(false);
    }
  }, [isCurrent]);

  const loadMore = useCallback(async () => {
    const generation = generationRef.current;
    const cursor = inbox.nextCursor;
    if (!cursor) return;
    try {
      const page = await listInAppMessages(activeRecipient, { after: cursor, size: PAGE_SIZE });
      if (!isCurrent(generation)) return;
      setInbox((current) => mergePage(current, page.content, page.unread, page.nextCursor));
    } catch (e) {
      if (!isCurrent(generation)) return;
      setError(e instanceof Error ? e.message : 'More messages could not be loaded');
    }
  }, [activeRecipient, inbox.nextCursor, isCurrent]);

  const recover = useCallback(
    async (recipient: string, fromCursor: string, generation: number): Promise<void> => {
      setRecoveryStalled(false);
      for (let attempt = 1; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
        try {
          let after: string | null = null;
          let snapshot: string | null = null;
          let pages = 0;

          while (pages < MAX_RECOVERY_PAGES) {
            const page = await listInAppMessages(recipient, { after, size: PAGE_SIZE });
            if (!isCurrent(generation)) return;
            pages++;
            setInbox((current) =>
              pages === 1
                ? applyFirstPage(page.content, page.unread, page.nextCursor)
                : mergePage(current, page.content, page.unread, page.nextCursor),
            );
            snapshot = highestSeq(page.content, snapshot);
            const oldest = page.content.length > 0 ? page.content[page.content.length - 1].seq : null;
            after = page.nextCursor;
            if (!page.page.hasMore || oldest === null || !isHigherSeq(oldest, fromCursor)) {
              break;
            }
          }

          const resumeAt = snapshot !== null && isHigherSeq(snapshot, fromCursor) ? snapshot : fromCursor;
          setStreamDetail(`recovered to ${resumeAt}`);
          streamRef.current?.resumeFrom(resumeAt);
          return;
        } catch (e) {
          if (!isCurrent(generation)) return;
          if (attempt === MAX_RECOVERY_ATTEMPTS) {
            setError(e instanceof Error ? e.message : 'The inbox could not be recovered');
            setStreamDetail('recovery failed; the stream is paused');
            setRecoveryStalled(true);
            return;
          }
          await new Promise((resolve) => setTimeout(resolve, Math.min(8000, 500 * 2 ** (attempt - 1))));
          if (!isCurrent(generation)) return;
        }
      }
    },
    [isCurrent],
  );

  useEffect(() => {
    const generation = ++generationRef.current;
    streamRef.current?.close();
    streamRef.current = null;
    setInbox(emptyInbox);
    setResyncs(0);
    setRecoveryStalled(false);
    setStreamDetail(null);

    void (async () => {
      const newestSeq = await load(activeRecipient, generation);
      if (!isCurrent(generation)) return;

      const stream = new InAppStream(
        activeRecipient,
        {
          onMessage: (event) => {
            if (!isCurrent(generation)) return;
            setInbox((current) =>
              applyIncoming(current, {
                seq: event.seq,
                notificationId: event.notificationId,
                subject: event.subject || null,
                body: event.body,
                read: event.read,
                readAt: null,
                createdAt: event.createdAt,
              }),
            );
          },
          onResync: (_reason, fromCursor) => {
            if (!isCurrent(generation)) return;
            setResyncs((count) => count + 1);
            void recover(activeRecipient, fromCursor, generation);
          },
          onState: (state, detail) => {
            if (!isCurrent(generation)) return;
            setStreamState(state);
            if (detail !== undefined) {
              setStreamDetail(detail);
            } else if (state === 'live') {
              setStreamDetail(null);
            }
          },
        },
        newestSeq,
      );
      streamRef.current = stream;
      stream.start();
    })();

    return () => {
      generationRef.current++;
      streamRef.current?.close();
      streamRef.current = null;
    };
  }, [activeRecipient, load, recover, isCurrent]);

  const onMarkRead = async (seq: string, alreadyRead: boolean) => {
    if (alreadyRead) return;
    const generation = generationRef.current;
    try {
      await markInAppMessageRead(activeRecipient, seq);
      if (!isCurrent(generation)) return;
      setInbox((current) => applyMarkedRead(current, seq));
    } catch (e) {
      if (!isCurrent(generation)) return;
      setError(e instanceof Error ? e.message : 'The message could not be marked read');
    }
  };

  const statusLabel = useMemo(() => {
    switch (streamState) {
      case 'live':
        return 'Live';
      case 'connecting':
        return 'Connecting';
      case 'waiting':
        return 'Waiting to reconnect';
      case 'recovering':
        return 'Catching up';
      case 'stopped':
        return 'Stopped';
      default:
        return 'Idle';
    }
  }, [streamState]);

  return (
    <section data-testid="inbox-page" className="page-container" style={{ maxWidth: 960, overflowWrap: 'anywhere' }}>
      {/* Header */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 14, marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <div style={{ width: 32, height: 32, borderRadius: 'var(--radius-control)', background: 'var(--color-st-processing-bg)', color: 'var(--color-signal)', display: 'grid', placeItems: 'center' }}>
            <Inbox size={17} />
          </div>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)' }}>
            In-App Inbox
          </h1>
          <span
            data-testid="inbox-unread"
            className="chip chip-processing"
            style={{ fontSize: 11, fontWeight: 700, padding: '2px 8px' }}
          >
            {inbox.unread} unread
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span
            data-testid="stream-status"
            className={`chip ${
              streamState === 'live'
                ? 'chip-delivered'
                : streamState === 'recovering' || streamState === 'connecting'
                ? 'chip-retrying'
                : streamState === 'waiting'
                ? 'chip-scheduled'
                : 'chip-queued'
            }`}
            aria-live="polite"
            style={{ fontSize: 11, padding: '2px 9px', display: 'inline-flex', alignItems: 'center', gap: 5 }}
          >
            <Radio size={11} style={{ opacity: streamState === 'live' ? 1 : 0.6 }} />
            {statusLabel}
          </span>
          {streamDetail && (
            <span
              data-testid="stream-detail"
              className="mono"
              style={{ fontSize: 11, color: 'var(--color-ink-2)', background: 'var(--color-chalk)', padding: '2px 7px', borderRadius: 4, border: '1px solid var(--color-rail)' }}
            >
              {streamDetail}
            </span>
          )}
        </div>
      </header>

      {/* Recipient Filter Form */}
      <div className="panel" style={{ padding: 14, marginBottom: 16 }}>
        <form
          style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}
          onSubmit={(event) => {
            event.preventDefault();
            setActiveRecipient(recipientRef.trim());
          }}
        >
          <label htmlFor="recipientRef" style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-harbor)' }}>
            Recipient ID:
          </label>
          <div style={{ position: 'relative', flex: '1 1 200px', minWidth: 0, maxWidth: '100%' }}>
            <input
              id="recipientRef"
              name="recipientRef"
              data-testid="recipient-input"
              value={recipientRef}
              onChange={(event) => setRecipientRef(event.target.value)}
              className="input-text mono"
              style={{ paddingLeft: 30, fontSize: 12 }}
            />
            <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--color-ink-2)' }} />
          </div>
          <button
            type="submit"
            data-testid="recipient-apply"
            className="btn btn-secondary"
            style={{ fontSize: 12 }}
          >
            Open Inbox
          </button>
        </form>
      </div>

      {error && (
        <div
          data-testid="inbox-error"
          role="alert"
          style={{
            padding: '10px 14px',
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

      {recoveryStalled && (
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'center' }}>
          <button
            type="button"
            data-testid="inbox-recover-retry"
            onClick={() =>
              void recover(activeRecipient, streamRef.current?.currentCursor() ?? '0', generationRef.current)
            }
            className="btn btn-secondary"
            style={{ fontSize: 12, color: 'var(--color-signal)' }}
          >
            <RefreshCw size={12} /> Retry Catching Up
          </button>
        </div>
      )}

      {loading && (
        <div data-testid="inbox-loading" className="panel" style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-2)' }}>
          <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
          <p style={{ margin: 0, fontSize: 13 }}>Loading the inbox…</p>
        </div>
      )}

      {!loading && inbox.messages.length === 0 && (
        <div data-testid="inbox-empty" className="panel" style={{ padding: 48, textAlign: 'center', color: 'var(--color-ink-2)' }}>
          <div style={{ width: 36, height: 36, borderRadius: 'var(--radius-control)', background: 'var(--color-chalk)', color: 'var(--color-ink-2)', display: 'grid', placeItems: 'center', margin: '0 auto 12px' }}>
            <Inbox size={18} />
          </div>
          <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-harbor)', marginBottom: 4 }}>No messages for this recipient yet.</div>
          <p style={{ margin: 0, fontSize: 12 }}>Notifications dispatched to this recipient will appear live via real-time SSE stream.</p>
        </div>
      )}

      <ul data-testid="inbox-list" style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {inbox.messages.map((message) => (
          <li
            key={message.seq}
            data-testid="inbox-item"
            data-seq={message.seq}
            data-read={message.read}
            className="panel"
            style={{
              padding: '12px 16px',
              borderLeft: message.read ? '1px solid var(--color-rail)' : '3px solid var(--color-signal)',
              backgroundColor: message.read ? 'var(--color-paper)' : 'var(--color-paper)',
              transition: 'all 0.15s ease',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 4 }}>
              <div
                data-testid="inbox-subject"
                style={{
                  fontSize: 13,
                  fontWeight: message.read ? 600 : 700,
                  color: 'var(--color-harbor)',
                }}
              >
                {message.subject ?? '(no subject)'}
              </div>
              <button
                type="button"
                data-testid="inbox-mark-read"
                onClick={() => void onMarkRead(message.seq, message.read)}
                disabled={message.read}
                className="btn btn-secondary"
                style={{
                  padding: '3px 8px',
                  fontSize: 11,
                  opacity: message.read ? 0.6 : 1,
                  cursor: message.read ? 'default' : 'pointer',
                  flexShrink: 0,
                }}
              >
                {message.read ? (
                  <>
                    <Check size={11} style={{ color: 'var(--color-st-delivered)' }} /> Read
                  </>
                ) : (
                  'Mark Read'
                )}
              </button>
            </div>
            <div
              data-testid="inbox-body"
              style={{
                fontSize: 12,
                color: 'var(--color-ink-2)',
                lineHeight: 1.5,
              }}
            >
              {message.body}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6, fontSize: 10, color: 'var(--color-ink-2)' }}>
              <span className="mono">seq: {message.seq}</span>
              <span>•</span>
              <span className="mono">id: {message.notificationId}</span>
            </div>
          </li>
        ))}
      </ul>

      {inbox.nextCursor && (
        <div style={{ marginTop: 14, textAlign: 'center' }}>
          <button
            type="button"
            data-testid="inbox-load-more"
            onClick={() => void loadMore()}
            className="btn btn-secondary"
            style={{ fontSize: 12 }}
          >
            <ChevronDown size={13} /> Load Older Messages
          </button>
        </div>
      )}

      <div style={{ marginTop: 20, textAlign: 'right' }}>
        <p data-testid="inbox-resyncs" className="mono" style={{ fontSize: 11, color: 'var(--color-ink-2)', margin: 0 }}>
          resyncs: {resyncs}
        </p>
      </div>
    </section>
  );
}