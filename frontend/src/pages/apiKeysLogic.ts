export interface ScopeDefinition {
  value: string;
  label: string;
  desc: string;
}

export const AVAILABLE_SCOPES: ScopeDefinition[] = [
  { value: 'notifications:send', label: 'Send Notifications', desc: 'Dispatch emails, webhooks, and in-app messages' },
  { value: 'notifications:read', label: 'Read Notifications', desc: 'Query status, history, and delivery attempts' },
  { value: 'notifications:cancel', label: 'Cancel Notifications', desc: 'Cancel pending scheduled or queued deliveries' },
  { value: 'templates:read', label: 'Read Templates', desc: 'View published template definitions' },
  { value: 'templates:write', label: 'Manage Templates', desc: 'Create, update, and publish templates' },
  { value: 'inapp:stream', label: 'In-App Stream', desc: 'Subscribe to real-time recipient events via SSE' },
  { value: 'usage:read', label: 'Read Usage Stats', desc: 'Access delivery metrics and rate limits' },
];

export const PRESET_SEND_ONLY = ['notifications:send'];
export const PRESET_FULL_ACCESS = AVAILABLE_SCOPES.map((s) => s.value);

export interface GracePeriodOption {
  value: number;
  label: string;
  desc: string;
}

export const GRACE_PERIOD_OPTIONS: GracePeriodOption[] = [
  { value: 0, label: 'Immediate', desc: 'The existing key will be revoked immediately.' },
  { value: 1, label: '1 hour', desc: 'The existing key will remain active for 1 hour to avoid downtime during service redeployments.' },
  { value: 24, label: '24 hours', desc: 'The existing key will remain active for 24 hours to avoid downtime during service redeployments.' },
  { value: 168, label: '7 days', desc: 'The existing key will remain active for 7 days to avoid downtime during service redeployments.' },
];

export function formatExpiry(expiresAtStr: string | null, nowMs: number = Date.now()): string {
  if (!expiresAtStr) return 'Never';
  const diffMs = new Date(expiresAtStr).getTime() - nowMs;
  if (diffMs <= 0) return 'Expired';
  const hoursLeft = Math.round(diffMs / (1000 * 60 * 60));
  if (hoursLeft <= 48) return `Expires in ${hoursLeft <= 0 ? '<1' : hoursLeft}h`;
  return new Date(expiresAtStr).toLocaleDateString();
}

export function describeGracePeriod(hours: number): string {
  const match = GRACE_PERIOD_OPTIONS.find((o) => o.value === hours);
  if (match) return match.desc;
  return `The existing key will remain active for ${hours} hours.`;
}
