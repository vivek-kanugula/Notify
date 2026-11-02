import type { Channel, NotificationStatus, NotificationSummary } from '../api/notifications';

export const FILTER_CHANNELS: Channel[] = ['EMAIL', 'WEBHOOK', 'IN_APP'];

/**
 * Filterable statuses for notifications list.
 * Note: CREATED is intentionally omitted as it is transient and immediately transitions to QUEUED.
 */
export const FILTER_STATUSES: NotificationStatus[] = [
  'SCHEDULED',
  'QUEUED',
  'PROCESSING',
  'RETRYING',
  'DELIVERED',
  'FAILED',
  'DLQ',
  'CANCELLED',
];

export interface NotificationFilterState {
  channel: Channel | '';
  status: NotificationStatus | '';
  search: string;
  page: number;
  size: number;
}

export function parseFilterState(searchParams: URLSearchParams): NotificationFilterState {
  const channelParam = searchParams.get('channel') as Channel | null;
  const statusParam = searchParams.get('status') as NotificationStatus | null;
  const search = searchParams.get('search') || '';
  const pageParam = parseInt(searchParams.get('page') || '0', 10);
  const sizeParam = parseInt(searchParams.get('size') || '20', 10);

  const channel: Channel | '' = channelParam && FILTER_CHANNELS.includes(channelParam) ? channelParam : '';
  const status: NotificationStatus | '' = statusParam && FILTER_STATUSES.includes(statusParam) ? statusParam : '';
  const page = isNaN(pageParam) || pageParam < 0 ? 0 : pageParam;
  const size = isNaN(sizeParam) || sizeParam <= 0 ? 20 : sizeParam;

  return { channel, status, search, page, size };
}

export function serializeFilterState(state: NotificationFilterState): URLSearchParams {
  const params = new URLSearchParams();
  if (state.channel) params.set('channel', state.channel);
  if (state.status) params.set('status', state.status);
  if (state.search.trim()) params.set('search', state.search.trim());
  if (state.page > 0) params.set('page', String(state.page));
  if (state.size !== 20) params.set('size', String(state.size));
  return params;
}

export function hasActiveFilters(state: NotificationFilterState): boolean {
  return Boolean(state.channel || state.status || state.search.trim() || state.page > 0);
}

export function filterNotificationsClientSide(
  items: NotificationSummary[],
  searchQuery: string
): NotificationSummary[] {
  const query = searchQuery.trim().toLowerCase();
  if (!query) return items;

  return items.filter((n) => {
    const recipientMatch = n.recipient.toLowerCase().includes(query);
    const subjectMatch = n.subject ? n.subject.toLowerCase().includes(query) : false;
    const idMatch = n.id.toLowerCase().includes(query);
    return recipientMatch || subjectMatch || idMatch;
  });
}
