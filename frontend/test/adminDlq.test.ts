import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  listAdminDlqEntries,
  getAdminDlqEntryDetail,
  replayDlqEntry,
  discardDlqEntry,
} from '../src/api/adminDlq.ts';
import { _resetAdminClientForTesting } from '../src/api/adminClient.ts';

describe('adminDlq.ts API request serialization & contract enforcement', () => {
  const originalFetch = globalThis.fetch;
  let lastRequest: { url: string; method?: string; headers: Headers; body?: string } | null = null;

  beforeEach(() => {
    lastRequest = null;
    _resetAdminClientForTesting();

    globalThis.fetch = async (url: any, options: any) => {
      lastRequest = {
        url: String(url),
        method: options?.method,
        headers: new Headers(options?.headers),
        body: options?.body,
      };

      if (lastRequest.url.endsWith('/replay')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            entryId: '00000000-0000-0000-0000-000000000001',
            notificationId: '00000000-0000-0000-0000-000000000002',
            status: 'OPEN',
            replayCount: 1,
            message: 'Replay initiated',
          }),
        } as any;
      }

      if (lastRequest.url.endsWith('/discard')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            entryId: '00000000-0000-0000-0000-000000000001',
            notificationId: '00000000-0000-0000-0000-000000000002',
            tenantId: '00000000-0000-0000-0000-000000000003',
            tenantSlug: 'acme-corp',
            channel: 'EMAIL',
            reason: 'EXHAUSTED',
            lastErrorCode: 'MAX_ATTEMPTS_EXCEEDED',
            attemptCount: 3,
            status: 'DISCARDED',
            replayCount: 0,
            resolutionNote: 'Discarded by operator due to invalid destination',
            resolvedBy: 'operator@notify.dev',
            resolvedAt: '2026-10-28T10:00:00Z',
            createdAt: '2026-10-28T09:00:00Z',
            updatedAt: '2026-10-28T10:00:00Z',
            notification: {
              currentStatus: 'FAILED',
              channel: 'EMAIL',
              currentDeliveryCycle: 1,
              currentDispatchGeneration: 1,
              currentAttemptCount: 3,
              maxAttempts: 3,
            },
            attempts: [],
            attemptTotalCount: 3,
            attemptsTruncated: false,
            history: [],
            historyTotalCount: 2,
            historyTruncated: false,
            quarantineRecordCount: 1,
            payloadSnapshot: '{"event":"DEAD_LETTER","reason":"EXHAUSTED"}',
            sourceDeliveryCycle: 1,
            sourceGeneration: 1,
          }),
        } as any;
      }

      if (lastRequest.url.includes('/api/v1/admin/dlq/')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            entryId: '00000000-0000-0000-0000-000000000001',
            notificationId: '00000000-0000-0000-0000-000000000002',
            tenantId: '00000000-0000-0000-0000-000000000003',
            tenantSlug: 'acme-corp',
            channel: 'WEBHOOK',
            reason: 'UNRECOVERABLE_FAILURE',
            lastErrorCode: 'HTTP_500',
            attemptCount: 5,
            status: 'OPEN',
            replayCount: 0,
            createdAt: '2026-10-28T08:00:00Z',
            updatedAt: '2026-10-28T08:30:00Z',
            notification: {
              currentStatus: 'FAILED',
              channel: 'WEBHOOK',
              currentDeliveryCycle: 1,
              currentDispatchGeneration: 1,
              currentAttemptCount: 5,
              maxAttempts: 5,
            },
            attempts: [
              {
                attemptNumber: 1,
                deliveryCycle: 1,
                outcome: 'FAILED',
                errorCode: 'HTTP_500',
                httpStatus: 500,
                provider: 'WEBHOOK',
                latencyMs: 142,
                startedAt: '2026-10-28T08:00:00Z',
                finishedAt: '2026-10-28T08:00:01Z',
              },
            ],
            attemptTotalCount: 5,
            attemptsTruncated: true,
            history: [
              {
                fromStatus: 'PROCESSING',
                toStatus: 'DLQ',
                actor: 'SYSTEM',
                reason: 'UNRECOVERABLE_FAILURE',
                occurredAt: '2026-10-28T08:30:00Z',
              },
            ],
            historyTotalCount: 3,
            historyTruncated: false,
            quarantineRecordCount: 1,
            payloadSnapshot: '{"event":"DEAD_LETTER","reason":"UNRECOVERABLE_FAILURE"}',
            sourceDeliveryCycle: 1,
            sourceGeneration: 1,
          }),
        } as any;
      }

      // Default list response
      return {
        ok: true,
        status: 200,
        json: async () => ({
          items: [
            {
              entryId: '00000000-0000-0000-0000-000000000001',
              notificationId: '00000000-0000-0000-0000-000000000002',
              tenantId: '00000000-0000-0000-0000-000000000003',
              tenantSlug: 'acme-corp',
              channel: 'EMAIL',
              reason: 'EXHAUSTED',
              lastErrorCode: 'TIMEOUT',
              attemptCount: 3,
              status: 'OPEN',
              replayCount: 0,
              createdAt: '2026-10-28T08:00:00Z',
              updatedAt: '2026-10-28T08:30:00Z',
            },
          ],
          nextCursor: 'eyJzZXEiOjEwMH0=',
          hasMore: true,
        }),
      } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetAdminClientForTesting();
  });

  test('listAdminDlqEntries serializes status, channel, reason, cursor and limit', async () => {
    const res = await listAdminDlqEntries({
      status: 'OPEN',
      channel: 'EMAIL',
      reason: 'EXHAUSTED',
      cursor: 'cur123',
      limit: 25,
    });

    assert.ok(lastRequest);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.pathname, '/api/v1/admin/dlq');
    assert.strictEqual(parsedUrl.searchParams.get('status'), 'OPEN');
    assert.strictEqual(parsedUrl.searchParams.get('channel'), 'EMAIL');
    assert.strictEqual(parsedUrl.searchParams.get('reason'), 'EXHAUSTED');
    assert.strictEqual(parsedUrl.searchParams.get('cursor'), 'cur123');
    assert.strictEqual(parsedUrl.searchParams.get('limit'), '25');
    assert.strictEqual(res.items.length, 1);
    assert.strictEqual(res.hasMore, true);
  });

  test('getAdminDlqEntryDetail returns attempts, history, truncation flags, and enforces zero recipient leak ', async () => {
    const detail = await getAdminDlqEntryDetail('00000000-0000-0000-0000-000000000001');

    assert.ok(lastRequest);
    assert.strictEqual(detail.entryId, '00000000-0000-0000-0000-000000000001');
    assert.strictEqual(detail.attemptsTruncated, true);
    assert.strictEqual(detail.attemptTotalCount, 5);
    assert.strictEqual(detail.historyTotalCount, 3);
    assert.strictEqual(detail.quarantineRecordCount, 1);
    assert.ok(detail.payloadSnapshot);

    // Security Invariant: DLQ detail projection contains no recipient field
    assert.strictEqual((detail as any).recipient, undefined);
  });

  test('replayDlqEntry dispatches POST with optional note', async () => {
    const res = await replayDlqEntry('00000000-0000-0000-0000-000000000001', 'Replay after provider fix');

    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.method, 'POST');
    assert.ok(lastRequest.url.endsWith('/replay'));
    const body = JSON.parse(lastRequest.body!);
    assert.strictEqual(body.note, 'Replay after provider fix');
    assert.strictEqual(res.status, 'OPEN');
  });

  test('discardDlqEntry enforces minimum 3 characters note floor on client before network call', async () => {
    // 1. Rejection on empty or short note
    await assert.rejects(
      async () => discardDlqEntry('00000000-0000-0000-0000-000000000001', 'ab'),
      { message: /at least 3 characters/ }
    );
    assert.strictEqual(lastRequest, null, 'No network request should be dispatched when note is < 3 characters');

    // 2. Success on >= 3 character note
    const discarded = await discardDlqEntry('00000000-0000-0000-0000-000000000001', 'Unrecoverable poison message');
    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.method, 'POST');
    assert.ok(lastRequest.url.endsWith('/discard'));
    const body = JSON.parse(lastRequest.body!);
    assert.strictEqual(body.note, 'Unrecoverable poison message');
    assert.strictEqual(discarded.status, 'DISCARDED');
  });
});
