import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import {
  getWebhookEndpoint,
  updateWebhookEndpoint,
  testWebhookEndpoint,
  rotateWebhookSecret,
  deleteWebhookEndpoint,
} from '../src/api/webhooks.ts';
import { _resetClientForTesting } from '../src/api/client.ts';

describe('webhooks.ts API endpoints and permanent deletion', () => {
  const originalFetch = globalThis.fetch;
  let lastRequest: { url: string; method?: string; headers: Headers; body?: string } | null = null;

  beforeEach(() => {
    lastRequest = null;
    _resetClientForTesting();

    globalThis.fetch = async (url: any, options: any) => {
      lastRequest = {
        url: String(url),
        method: options?.method,
        headers: new Headers(options?.headers),
        body: options?.body,
      };

      if (lastRequest.url.endsWith('/api/v1/webhook-endpoint') && options?.method === 'DELETE') {
        return { ok: true, status: 204 } as any;
      }

      if (lastRequest.url.endsWith('/api/v1/webhook-endpoint/rotate-secret') && options?.method === 'POST') {
        return {
          ok: true,
          status: 201,
          json: async () => ({
            id: 'wh-123',
            url: 'https://example.com/hook',
            isDemo: false,
            enabled: true,
            statusCallbacks: false,
            createdAt: '2026-11-06T10:00:00Z',
            updatedAt: '2026-11-06T12:00:00Z',
            signingSecret: 'whsec_newsecret123',
          }),
        } as any;
      }

      if (lastRequest.url.endsWith('/api/v1/webhook-endpoint/test') && options?.method === 'POST') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            delivered: true,
            httpStatus: 200,
            outcome: 'SUCCESS',
            errorCode: null,
            detail: null,
          }),
        } as any;
      }

      if (lastRequest.url.endsWith('/api/v1/webhook-endpoint') && options?.method === 'PUT') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            id: 'wh-123',
            url: JSON.parse(options?.body || '{}').url,
            isDemo: false,
            enabled: true,
            statusCallbacks: false,
            createdAt: '2026-11-06T10:00:00Z',
            updatedAt: '2026-11-06T12:00:00Z',
          }),
        } as any;
      }

      return {
        ok: true,
        status: 200,
        json: async () => ({
          id: 'wh-123',
          url: 'https://example.com/hook',
          isDemo: false,
          enabled: true,
          statusCallbacks: false,
          createdAt: '2026-11-06T10:00:00Z',
          updatedAt: '2026-11-06T10:00:00Z',
        }),
      } as any;
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    _resetClientForTesting();
  });

  test('getWebhookEndpoint requests GET /api/v1/webhook-endpoint', async () => {
    const endpoint = await getWebhookEndpoint();
    assert.ok(endpoint);
    assert.strictEqual(endpoint.id, 'wh-123');
    assert.strictEqual(lastRequest?.url, '/api/v1/webhook-endpoint');
    assert.strictEqual(lastRequest?.method ?? 'GET', 'GET');
  });

  test('updateWebhookEndpoint sends PUT with JSON payload', async () => {
    const updated = await updateWebhookEndpoint({
      url: 'https://webhook.site/abc',
      enabled: true,
    });
    assert.strictEqual(updated.url, 'https://webhook.site/abc');
    assert.strictEqual(lastRequest?.method, 'PUT');
    assert.strictEqual(lastRequest?.url, '/api/v1/webhook-endpoint');
    assert.deepStrictEqual(JSON.parse(lastRequest?.body || '{}'), {
      url: 'https://webhook.site/abc',
      enabled: true,
    });
  });

  test('testWebhookEndpoint sends POST to /api/v1/webhook-endpoint/test', async () => {
    const result = await testWebhookEndpoint();
    assert.strictEqual(result.delivered, true);
    assert.strictEqual(lastRequest?.method, 'POST');
    assert.strictEqual(lastRequest?.url, '/api/v1/webhook-endpoint/test');
  });

  test('rotateWebhookSecret sends POST to /api/v1/webhook-endpoint/rotate-secret', async () => {
    const rotated = await rotateWebhookSecret();
    assert.strictEqual(rotated.signingSecret, 'whsec_newsecret123');
    assert.strictEqual(lastRequest?.method, 'POST');
    assert.strictEqual(lastRequest?.url, '/api/v1/webhook-endpoint/rotate-secret');
  });

  test('deleteWebhookEndpoint sends DELETE to /api/v1/webhook-endpoint to permanently delete from DB', async () => {
    await deleteWebhookEndpoint();
    assert.ok(lastRequest);
    assert.strictEqual(lastRequest.method, 'DELETE');
    assert.strictEqual(lastRequest.url, '/api/v1/webhook-endpoint');
  });
});
