import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import { getAdminSystemStatus } from '../src/api/adminSystem.ts';
import { _resetAdminClientForTesting } from '../src/api/adminClient.ts';

describe('adminSystem.ts API request & telemetry contract', () => {
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

      return {
        ok: true,
        status: 200,
        json: async () => ({
          status: 'UP',
          timestamp: '2026-10-28T10:15:00Z',
          components: {
            db: { status: 'UP', details: { database: 'PostgreSQL', poolActive: 4, poolIdle: 16 } },
            redis: { status: 'UP', details: { pingLatencyMs: 1.2 } },
            kafka: { status: 'UP', details: { clusterId: 'notify-cluster', brokers: 3 } },
            quartz: { status: 'UP', details: { schedulerName: 'NotifyQuartzScheduler', threadPoolSize: 10 } },
          },
          circuitBreakers: [
            { name: 'emailCircuit', state: 'CLOSED', failureRate: 0.0 },
            { name: 'webhookCircuit', state: 'HALF_OPEN', failureRate: 24.5 },
          ],
          grafanaLinks: {
            overview: 'http://localhost:3000/d/notify-overview',
            logs: 'http://localhost:3000/explore?left=%5B%22now-1h%22,%22now%22,%22Loki%22%5D',
            traces: 'http://localhost:3000/explore?left=%5B%22now-1h%22,%22now%22,%22Tempo%22%5D',
          },
        }),
      } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetAdminClientForTesting();
  });

  test('getAdminSystemStatus parses health roll-up, component status, and circuit breakers', async () => {
    const status = await getAdminSystemStatus();

    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.url, '/api/v1/admin/system/status');
    assert.strictEqual(status.status, 'UP');
    assert.strictEqual(status.components['db']?.status, 'UP');
    assert.strictEqual(status.components['redis']?.status, 'UP');
    assert.strictEqual(status.components['kafka']?.status, 'UP');
    assert.strictEqual(status.components['quartz']?.status, 'UP');
    assert.strictEqual(status.circuitBreakers.length, 2);
    assert.strictEqual(status.circuitBreakers[0].name, 'emailCircuit');
    assert.strictEqual(status.circuitBreakers[0].state, 'CLOSED');
    assert.strictEqual(status.circuitBreakers[1].state, 'HALF_OPEN');
    assert.ok(status.grafanaLinks['overview']);
    assert.ok(status.grafanaLinks['logs']);
    assert.ok(status.grafanaLinks['traces']);
  });
});
