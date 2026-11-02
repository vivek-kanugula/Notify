import type { Channel, NotificationStatus } from './notifications';

export interface DemoInboxItem {
  id: string;
  tenantSlug: string | null;
  tenantName: string | null;
  channel: Channel;
  status: NotificationStatus;
  recipient: string;
  subject: string | null;
  createdAt: string;
}

export type DemoStreamStatus = 'LIVE' | 'PAUSED' | 'RECONNECTING' | 'OFFLINE';

export interface ConnectDemoStreamOptions {
  onItem: (item: DemoInboxItem) => void;
  onStatusChange: (status: DemoStreamStatus) => void;
}

/**
 * Fetches the initial public demo inbox notifications .
 * Returns up to 50 recent notifications from configured demo tenants.
 */
export async function fetchDemoInbox(): Promise<DemoInboxItem[]> {
  const res = await fetch('/api/v1/demo/inbox', {
    headers: {
      Accept: 'application/json',
    },
  });

  if (!res.ok) {
    if (res.status === 404) {
      throw new Error('DEMO_DISABLED');
    }
    throw new Error(`Failed to load demo inbox: HTTP ${res.status}`);
  }

  return (await res.json()) as DemoInboxItem[];
}

/**
 * Opens an SSE connection to the public demo stream .
 * Connects to /demo/stream (falling back to /api/v1/demo/stream if needed).
 * Handles automatic reconnect with exponential backoff and returns an unsubscribe function.
 */
export function connectDemoStream(options: ConnectDemoStreamOptions): () => void {
  const { onItem, onStatusChange } = options;

  let eventSource: EventSource | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let closed = false;
  let backoffMs = 1000;
  const maxBackoffMs = 15000;

  function connect() {
    if (closed) return;

    try {
      // Use /api/v1/demo/stream which is always proxied cleanly by Vite dev server and backend
      const url = '/api/v1/demo/stream';
      eventSource = new EventSource(url);

      eventSource.onopen = () => {
        if (closed) {
          eventSource?.close();
          return;
        }
        backoffMs = 1000; // Reset backoff on successful connection
        onStatusChange('LIVE');
      };

      eventSource.addEventListener('message', (event) => {
        if (closed) return;
        try {
          const item = JSON.parse(event.data) as DemoInboxItem;
          if (item && item.id) {
            onItem(item);
          }
        } catch {
          // Ignore malformed JSON frame
        }
      });

      eventSource.onerror = () => {
        if (closed) return;
        eventSource?.close();
        eventSource = null;
        onStatusChange('RECONNECTING');

        // Exponential backoff reconnect
        reconnectTimer = setTimeout(() => {
          if (!closed) {
            backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
            connect();
          }
        }, backoffMs);
      };
    } catch {
      onStatusChange('OFFLINE');
    }
  }

  connect();

  return () => {
    closed = true;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
    onStatusChange('OFFLINE');
  };
}
