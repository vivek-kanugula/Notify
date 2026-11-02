import { test, describe } from 'node:test';
import assert from 'node:assert';
import {
  parseFilterState,
  serializeFilterState,
  hasActiveFilters,
  filterNotificationsClientSide,
  FILTER_CHANNELS,
  FILTER_STATUSES,
} from '../src/components/notificationsFilterLogic.ts';
import {
  extractTemplateVariables,
  interpolateTemplateVariables,
} from '../src/components/templateVariableUtils.ts';
import type { NotificationSummary } from '../src/api/notifications.ts';

describe('Notifications Filter URL & State Synchronization', () => {
  test('parseFilterState extracts valid parameters and establishes clean defaults', () => {
    const params = new URLSearchParams('channel=EMAIL&status=DELIVERED&search=alice&page=2&size=50');
    const state = parseFilterState(params);

    assert.strictEqual(state.channel, 'EMAIL');
    assert.strictEqual(state.status, 'DELIVERED');
    assert.strictEqual(state.search, 'alice');
    assert.strictEqual(state.page, 2);
    assert.strictEqual(state.size, 50);
  });

  test('parseFilterState establishes defaults for empty URL query', () => {
    const params = new URLSearchParams('');
    const state = parseFilterState(params);

    assert.strictEqual(state.channel, '');
    assert.strictEqual(state.status, '');
    assert.strictEqual(state.search, '');
    assert.strictEqual(state.page, 0);
    assert.strictEqual(state.size, 20);
  });

  test('parseFilterState ignores invalid channels and invalid statuses', () => {
    const params = new URLSearchParams('channel=SMS&status=UNKNOWN_STATUS');
    const state = parseFilterState(params);

    assert.strictEqual(state.channel, '');
    assert.strictEqual(state.status, '');
  });

  test('parseFilterState rejects CREATED status as filterable since it is transient', () => {
    const params = new URLSearchParams('status=CREATED');
    const state = parseFilterState(params);

    // CREATED is omitted from FILTER_STATUSES per post-review resolution
    assert.strictEqual(state.status, '');
    assert.strictEqual(FILTER_STATUSES.includes('CREATED' as any), false);
  });

  test('serializeFilterState serializes active filters and omits default values', () => {
    const state = {
      channel: 'WEBHOOK' as const,
      status: 'FAILED' as const,
      search: 'api.example.com',
      page: 1,
      size: 20, // default size omitted
    };

    const params = serializeFilterState(state);
    assert.strictEqual(params.get('channel'), 'WEBHOOK');
    assert.strictEqual(params.get('status'), 'FAILED');
    assert.strictEqual(params.get('search'), 'api.example.com');
    assert.strictEqual(params.get('page'), '1');
    assert.strictEqual(params.has('size'), false);
  });

  test('hasActiveFilters detects when active filters exist', () => {
    assert.strictEqual(hasActiveFilters({ channel: '', status: '', search: '', page: 0, size: 20 }), false);
    assert.strictEqual(hasActiveFilters({ channel: 'EMAIL', status: '', search: '', page: 0, size: 20 }), true);
    assert.strictEqual(hasActiveFilters({ channel: '', status: 'QUEUED', search: '', page: 0, size: 20 }), true);
    assert.strictEqual(hasActiveFilters({ channel: '', status: '', search: 'bob', page: 0, size: 20 }), true);
    assert.strictEqual(hasActiveFilters({ channel: '', status: '', search: '', page: 1, size: 20 }), true);
  });
});

describe('Client-side notifications search filter', () => {
  const sampleNotifications: NotificationSummary[] = [
    {
      id: 'ntf-001',
      channel: 'EMAIL',
      status: 'DELIVERED',
      recipient: 'alice@example.com',
      subject: 'Order #1092 Confirmation',
      scheduledAt: null,
      createdAt: '2026-10-27T10:00:00Z',
    },
    {
      id: 'ntf-002',
      channel: 'WEBHOOK',
      status: 'QUEUED',
      recipient: 'https://api.merchant.com/webhook',
      subject: null,
      scheduledAt: null,
      createdAt: '2026-10-27T10:01:00Z',
    },
    {
      id: 'ntf-003',
      channel: 'IN_APP',
      status: 'PROCESSING',
      recipient: 'usr_charlie_88',
      subject: 'Welcome to Notify',
      scheduledAt: null,
      createdAt: '2026-10-27T10:02:00Z',
    },
  ];

  test('returns all items when query is empty', () => {
    const results = filterNotificationsClientSide(sampleNotifications, '');
    assert.strictEqual(results.length, 3);
  });

  test('filters by recipient email or URL case-insensitively', () => {
    const emailResults = filterNotificationsClientSide(sampleNotifications, 'ALICE');
    assert.strictEqual(emailResults.length, 1);
    assert.strictEqual(emailResults[0].id, 'ntf-001');

    const webhookResults = filterNotificationsClientSide(sampleNotifications, 'merchant.com');
    assert.strictEqual(webhookResults.length, 1);
    assert.strictEqual(webhookResults[0].id, 'ntf-002');
  });

  test('filters by subject', () => {
    const results = filterNotificationsClientSide(sampleNotifications, 'Confirmation');
    assert.strictEqual(results.length, 1);
    assert.strictEqual(results[0].id, 'ntf-001');
  });

  test('filters by notification ID', () => {
    const results = filterNotificationsClientSide(sampleNotifications, 'ntf-003');
    assert.strictEqual(results.length, 1);
    assert.strictEqual(results[0].id, 'ntf-003');
  });
});

describe('Template variable detection and interpolation', () => {
  test('extracts unique variable names from subject and body strings', () => {
    const subject = 'Order {{order_id}} for {{customer_name}}';
    const body = 'Hello {{customer_name}}, your order {{order_id}} has shipped. Tracking: {{tracking_code}}.';

    const vars = extractTemplateVariables(subject, body);
    assert.deepStrictEqual(vars.sort(), ['customer_name', 'order_id', 'tracking_code'].sort());
  });

  test('interpolates variable values accurately into template text', () => {
    const text = 'Hello {{name}}, order {{id}} is confirmed!';
    const result = interpolateTemplateVariables(text, {
      name: 'Alice',
      id: '9912',
    });

    assert.strictEqual(result, 'Hello Alice, order 9912 is confirmed!');
  });

  test('leaves unsupplied variables untouched without throwing', () => {
    const text = 'Hello {{name}}, your balance is {{balance}}.';
    const result = interpolateTemplateVariables(text, { name: 'Bob' });

    assert.strictEqual(result, 'Hello Bob, your balance is {{balance}}.');
  });

  test('does not leak or resolve prototype properties like toString, valueOf, or constructor', () => {
    const text = 'Testing {{toString}} and {{valueOf}} and {{constructor}}';
    const result = interpolateTemplateVariables(text, {});

    assert.strictEqual(result, 'Testing {{toString}} and {{valueOf}} and {{constructor}}');
  });
});
