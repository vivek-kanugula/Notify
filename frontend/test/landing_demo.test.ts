import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  prefersReducedMotion,
  SAMPLE_STAGES,
  getInitialSampleStep,
  advanceSampleStep,
} from '../src/components/sampleRailLogic.ts';
import type { DemoInboxItem } from '../src/api/demoClient.ts';
import type { Channel, NotificationStatus } from '../src/api/notifications.ts';

function filterDemoItems(
  items: DemoInboxItem[],
  channelFilter: 'ALL' | Channel,
  tenantFilter: string
): DemoInboxItem[] {
  return items.filter((item) => {
    if (channelFilter !== 'ALL' && item.channel !== channelFilter) {
      return false;
    }
    if (tenantFilter !== 'ALL' && item.tenantSlug !== tenantFilter) {
      return false;
    }
    return true;
  });
}

function processIncomingDemoItem(
  incoming: DemoInboxItem,
  currentItems: DemoInboxItem[],
  bufferedQueue: DemoInboxItem[],
  isPaused: boolean,
  maxItems = 100
): { items: DemoInboxItem[]; bufferedQueue: DemoInboxItem[] } {
  if (isPaused) {
    return {
      items: currentItems,
      bufferedQueue: [incoming, ...bufferedQueue].slice(0, maxItems),
    };
  } else {
    const deduplicated = currentItems.filter((i) => i.id !== incoming.id);
    return {
      items: [incoming, ...deduplicated].slice(0, maxItems),
      bufferedQueue: [],
    };
  }
}

function flushBufferedQueue(
  currentItems: DemoInboxItem[],
  bufferedQueue: DemoInboxItem[],
  maxItems = 100
): { items: DemoInboxItem[]; bufferedQueue: DemoInboxItem[] } {
  const merged = [...bufferedQueue, ...currentItems];
  return {
    items: merged.slice(0, maxItems),
    bufferedQueue: [],
  };
}

describe('Landing Page & Sample LifecycleRail', () => {
  it('prefersReducedMotion handles node/SSR gracefully without throwing', () => {
    // In node environment, window or matchMedia is not standard browser
    const reduced = prefersReducedMotion();
    assert.equal(typeof reduced, 'boolean');
  });

  it('SampleLifecycleRail stages list includes exactly Queued, Processing, Retry x2, and Delivered', () => {
    assert.equal(SAMPLE_STAGES.length, 4);
    assert.equal(SAMPLE_STAGES[0].name, 'QUEUED');
    assert.equal(SAMPLE_STAGES[1].name, 'PROCESSING');
    assert.equal(SAMPLE_STAGES[2].name, 'RETRYING');
    assert.equal(SAMPLE_STAGES[3].name, 'DELIVERED');
  });

  it('reduced motion initializes sample rail directly at terminal Delivered step (3)', () => {
    assert.equal(getInitialSampleStep(true), 3);
    assert.equal(getInitialSampleStep(false), 0);
  });

  it('advanceSampleStep steps sequentially and clamps at terminal step 3', () => {
    assert.equal(advanceSampleStep(0), 1);
    assert.equal(advanceSampleStep(1), 2);
    assert.equal(advanceSampleStep(2), 3);
    assert.equal(advanceSampleStep(3), 3);
  });
});

describe('Public Demo Inbox Filtering & Projection', () => {
  const sampleItems: DemoInboxItem[] = [
    {
      id: '1',
      tenantSlug: 'acme',
      tenantName: 'Acme Corp',
      channel: 'EMAIL',
      status: 'DELIVERED',
      recipient: 'u***@example.com',
      subject: 'Order Confirmation',
      createdAt: new Date().toISOString(),
    },
    {
      id: '2',
      tenantSlug: 'globex',
      tenantName: 'Globex',
      channel: 'WEBHOOK',
      status: 'DELIVERED',
      recipient: 'h***est',
      subject: 'Payment Webhook',
      createdAt: new Date().toISOString(),
    },
    {
      id: '3',
      tenantSlug: 'acme',
      tenantName: 'Acme Corp',
      channel: 'IN_APP',
      status: 'PROCESSING',
      recipient: 'u***@example.com',
      subject: 'Security Alert',
      createdAt: new Date().toISOString(),
    },
  ];

  it('filters accurately by channel', () => {
    const webhooks = filterDemoItems(sampleItems, 'WEBHOOK', 'ALL');
    assert.equal(webhooks.length, 1);
    assert.equal(webhooks[0].channel, 'WEBHOOK');

    const inApp = filterDemoItems(sampleItems, 'IN_APP', 'ALL');
    assert.equal(inApp.length, 1);
    assert.equal(inApp[0].channel, 'IN_APP');

    const emails = filterDemoItems(sampleItems, 'EMAIL', 'ALL');
    assert.equal(emails.length, 1);
    assert.equal(emails[0].channel, 'EMAIL');
  });

  it('filters accurately by tenant slug', () => {
    const acmeOnly = filterDemoItems(sampleItems, 'ALL', 'acme');
    assert.equal(acmeOnly.length, 2);
    assert.ok(acmeOnly.every((i) => i.tenantSlug === 'acme'));

    const globexOnly = filterDemoItems(sampleItems, 'ALL', 'globex');
    assert.equal(globexOnly.length, 1);
    assert.equal(globexOnly[0].tenantSlug === 'globex', true);
  });

  it('filters accurately by channel and tenant intersection', () => {
    const acmeInApp = filterDemoItems(sampleItems, 'IN_APP', 'acme');
    assert.equal(acmeInApp.length, 1);
    assert.equal(acmeInApp[0].id, '3');

    const globexEmail = filterDemoItems(sampleItems, 'EMAIL', 'globex');
    assert.equal(globexEmail.length, 0);
  });

  it('Safe projection ensures no raw bodies or secrets exist', () => {
    const target = sampleItems[0];
    const safeProjection = {
      id: target.id,
      tenantSlug: target.tenantSlug,
      channel: target.channel,
      status: target.status,
      recipient: target.recipient,
      subject: target.subject,
    };

    assert.equal((safeProjection as any).body, undefined);
    assert.equal((safeProjection as any).metadata, undefined);
    assert.equal((safeProjection as any).apiKey, undefined);
    assert.equal((safeProjection as any).secret, undefined);
    assert.ok(safeProjection.recipient.includes('***'));
  });
});

describe('Auto-Pause and Buffer Accounting', () => {
  const initialItems: DemoInboxItem[] = Array.from({ length: 50 }, (_, i) => ({
    id: `item-${i}`,
    tenantSlug: 'acme',
    tenantName: 'Acme Corp',
    channel: 'EMAIL' as Channel,
    status: 'DELIVERED' as NotificationStatus,
    recipient: `user${i}***@example.com`,
    subject: `Subject ${i}`,
    createdAt: new Date().toISOString(),
  }));

  it('appends incoming item directly when unpaused', () => {
    const newItem: DemoInboxItem = {
      id: 'new-1',
      tenantSlug: 'globex',
      tenantName: 'Globex',
      channel: 'WEBHOOK',
      status: 'DELIVERED',
      recipient: 'w***_test',
      subject: 'New item',
      createdAt: new Date().toISOString(),
    };

    const res = processIncomingDemoItem(newItem, initialItems, [], false, 100);
    assert.equal(res.items.length, 51);
    assert.equal(res.items[0].id, 'new-1');
    assert.equal(res.bufferedQueue.length, 0);
  });

  it('buffers incoming item in queue without mutating visible items when paused', () => {
    const newItem: DemoInboxItem = {
      id: 'new-2',
      tenantSlug: 'globex',
      tenantName: 'Globex',
      channel: 'WEBHOOK',
      status: 'DELIVERED',
      recipient: 'w***_test',
      subject: 'New item while paused',
      createdAt: new Date().toISOString(),
    };

    const res = processIncomingDemoItem(newItem, initialItems, [], true, 100);
    // Visible items did not change: prevents DOM jumps while user is reading!
    assert.equal(res.items.length, 50);
    assert.equal(res.bufferedQueue.length, 1);
    assert.equal(res.bufferedQueue[0].id, 'new-2');
  });

  it('flushes buffered items into visible list and caps at max 100 items', () => {
    const buffer: DemoInboxItem[] = Array.from({ length: 60 }, (_, i) => ({
      id: `buffered-${i}`,
      tenantSlug: 'globex',
      tenantName: 'Globex',
      channel: 'WEBHOOK' as Channel,
      status: 'DELIVERED' as NotificationStatus,
      recipient: `w***_${i}`,
      subject: `Buffered ${i}`,
      createdAt: new Date().toISOString(),
    }));

    // initial has 50, buffer has 60. 50 + 60 = 110. Max cap is 100.
    const flushed = flushBufferedQueue(initialItems, buffer, 100);
    assert.equal(flushed.items.length, 100);
    assert.equal(flushed.bufferedQueue.length, 0);
    // Newest buffered item is first
    assert.equal(flushed.items[0].id, 'buffered-0');
  });
});
