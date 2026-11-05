import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  searchAdminNotifications,
  getAdminNotificationDetail,
} from '../src/api/adminNotifications.ts';
import { _resetAdminClientForTesting } from '../src/api/adminClient.ts';

describe('adminNotifications.ts search and cross-tenant detail', () => {
  const originalFetch = globalThis.fetch;
  let lastRequest: { url: string; headers: Headers } | null = null;

  beforeEach(() => {
    lastRequest = null;
    _resetAdminClientForTesting();

    globalThis.fetch = async (url: any, options: any) => {
      lastRequest = {
        url: String(url),
        headers: new Headers(options?.headers),
      };

      if (lastRequest.url.includes('/api/v1/admin/notifications/')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            id: '11111111-1111-1111-1111-111111111111',
            tenantId: '22222222-2222-2222-2222-222222222222',
            channel: 'EMAIL',
            status: 'DELIVERED',
            recipient: 'ops@customer.com',
            subject: 'Monthly Invoice',
            scheduledAt: null,
            createdAt: '2026-10-27T10:00:00Z',
            firstProcessingAt: '2026-10-27T10:00:01Z',
            completedAt: '2026-10-27T10:00:02Z',
            attemptCount: 1,
            lastErrorCode: null,
            provider: 'RESEND',
            providerMessageId: 'msg_987654',
            metadata: null,
            deliveryAttempts: [
              {
                attemptNumber: 1,
                provider: 'RESEND',
                status: 'DELIVERED',
                errorCode: null,
                httpStatus: 200,
                attemptedAt: '2026-10-27T10:00:01Z',
                durationMs: 142,
              },
            ],
            statusHistory: [
              {
                id: 1,
                fromStatus: null,
                toStatus: 'QUEUED',
                reason: null,
                actor: 'SYSTEM',
                occurredAt: '2026-10-27T10:00:00Z',
              },
              {
                id: 2,
                fromStatus: 'QUEUED',
                toStatus: 'DELIVERED',
                reason: 'Dispatched successfully',
                actor: 'SYSTEM',
                occurredAt: '2026-10-27T10:00:02Z',
              },
            ],
          }),
        } as any;
      }

      return {
        ok: true,
        status: 200,
        json: async () => ({
          content: [],
          totalElements: 0,
          totalPages: 0,
          size: 20,
          number: 0,
        }),
      } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetAdminClientForTesting();
  });

  test('searchAdminNotifications serializes tenantId, channel, status, and range', async () => {
    await searchAdminNotifications({
      tenantId: '33333333-3333-3333-3333-333333333333',
      tenantName: 'Acme Corp',
      channel: 'WEBHOOK',
      status: 'FAILED',
      from: '2026-10-01T00:00:00Z',
      to: '2026-10-27T00:00:00Z',
      page: 1,
      size: 50,
    });

    assert.ok(lastRequest);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.searchParams.get('tenantId'), '33333333-3333-3333-3333-333333333333');
    assert.strictEqual(parsedUrl.searchParams.get('tenantName'), 'Acme Corp');
    assert.strictEqual(parsedUrl.searchParams.get('channel'), 'WEBHOOK');
    assert.strictEqual(parsedUrl.searchParams.get('status'), 'FAILED');
    assert.strictEqual(parsedUrl.searchParams.get('from'), '2026-10-01T00:00:00Z');
    assert.strictEqual(parsedUrl.searchParams.get('to'), '2026-10-27T00:00:00Z');
    assert.strictEqual(parsedUrl.searchParams.get('page'), '1');
    assert.strictEqual(parsedUrl.searchParams.get('size'), '50');
  });

  test('searchAdminNotifications serializes tenantName filter independently', async () => {
    await searchAdminNotifications({
      tenantName: 'Globex Inc',
      status: 'DELIVERED',
    });

    assert.ok(lastRequest);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.searchParams.get('tenantName'), 'Globex Inc');
    assert.strictEqual(parsedUrl.searchParams.get('status'), 'DELIVERED');
    assert.strictEqual(parsedUrl.searchParams.get('tenantId'), null);
  });

  test('getAdminNotificationDetail returns timeline and attempts without raw body', async () => {
    const detail = await getAdminNotificationDetail('11111111-1111-1111-1111-111111111111');
    assert.strictEqual(detail.id, '11111111-1111-1111-1111-111111111111');
    assert.strictEqual(detail.channel, 'EMAIL');
    assert.strictEqual(detail.status, 'DELIVERED');
    assert.strictEqual(detail.deliveryAttempts.length, 1);
    assert.strictEqual(detail.statusHistory.length, 2);
    // Security invariant: body is not present in admin detail response
    assert.strictEqual((detail as any).body, undefined);
  });
});
