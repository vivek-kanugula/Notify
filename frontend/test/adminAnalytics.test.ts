import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  getAdminOverviewAnalytics,
  getAdminTenantUsage,
} from '../src/api/adminAnalytics.ts';
import { _resetAdminClientForTesting } from '../src/api/adminClient.ts';

describe('adminAnalytics.ts API request serialization & contract enforcement', () => {
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

      if (lastRequest.url.includes('/analytics/overview')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            from: '2026-10-01T00:00:00Z',
            to: '2026-10-28T00:00:00Z',
            totalNotifications: 15420,
            deliveredCount: 14890,
            failedCount: 420,
            cancelledCount: 80,
            activeCount: 30,
            globalSuccessRate: 96.56,
            latencyPercentilesMs: { p50: 120, p95: 450, p99: 980 },
            queueDepth: 12,
            dlqOpenCount: 5,
            topTenants: [
              { tenantId: '00000000-0000-0000-0000-000000000001', slug: 'acme-corp', name: 'Acme Corp', volume: 5400 },
            ],
            topFailureReasons: [
              { reason: 'PROVIDER_RATE_LIMITED', count: 210 },
            ],
            statusCounts: {
              DELIVERED: 14890,
              FAILED: 415,
              DLQ: 5,
              CANCELLED: 80,
              QUEUED: 12,
              PROCESSING: 18,
              SCHEDULED: 0,
              RETRYING: 0,
            },
          }),
        } as any;
      }

      return {
        ok: true,
        status: 200,
        json: async () => ({
          tenantId: '00000000-0000-0000-0000-000000000001',
          from: '2026-10-01T00:00:00Z',
          to: '2026-10-28T00:00:00Z',
          totalCreated: 5400,
          deliveredCount: 5200,
          failedCount: 150,
          cancelledCount: 40,
          activeCount: 10,
          channelBreakdown: { EMAIL: 3000, WEBHOOK: 2000, IN_APP: 400 },
          statusBreakdown: { DELIVERED: 5200, FAILED: 150, CANCELLED: 40, QUEUED: 10 },
        }),
      } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetAdminClientForTesting();
  });

  test('getAdminOverviewAnalytics serializes from and to timestamps', async () => {
    const res = await getAdminOverviewAnalytics({
      from: '2026-10-01T00:00:00Z',
      to: '2026-10-28T00:00:00Z',
    });

    assert.ok(lastRequest);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.pathname, '/api/v1/admin/analytics/overview');
    assert.strictEqual(parsedUrl.searchParams.get('from'), '2026-10-01T00:00:00Z');
    assert.strictEqual(parsedUrl.searchParams.get('to'), '2026-10-28T00:00:00Z');
    assert.strictEqual(res.totalNotifications, 15420);
    assert.strictEqual(res.deliveredCount, 14890);
    assert.strictEqual(res.dlqOpenCount, 5);
    assert.strictEqual(res.statusCounts?.DELIVERED, 14890);
    assert.strictEqual(res.statusCounts?.QUEUED, 12);
  });

  test('getAdminTenantUsage calls correct tenant endpoint and parses breakdown', async () => {
    const res = await getAdminTenantUsage('00000000-0000-0000-0000-000000000001', {
      from: '2026-10-01T00:00:00Z',
    });

    assert.ok(lastRequest);
    const parsedUrl = new URL(lastRequest.url, 'http://localhost');
    assert.strictEqual(parsedUrl.pathname, '/api/v1/admin/tenants/00000000-0000-0000-0000-000000000001/usage');
    assert.strictEqual(parsedUrl.searchParams.get('from'), '2026-10-01T00:00:00Z');
    assert.strictEqual(res.totalCreated, 5400);
    assert.strictEqual(res.channelBreakdown['EMAIL'], 3000);
  });
});
