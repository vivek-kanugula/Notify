import { test, expect, APIRequestContext } from '@playwright/test';

// delivery journeys over real HTTP against the isolated stack (worker enabled, FAKE provider only).
// No live email: recipients use the reserved fake-provider domain or example.com, and the fake provider never
// contacts a network.

async function signup(request: APIRequestContext) {
 const id = `${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
 const res = await request.post('/auth/signup', {
 data: { tenantName: `Demo Org ${id}`, email: `demo_${id}@notify.local`, password: 'Password123!' },
 });
 expect(res.status()).toBe(201);
 const { accessToken } = await res.json();
 const keyRes = await request.post('/api/v1/keys', {
 headers: { Authorization: `Bearer ${accessToken}` },
 data: { name: `b7-key-${id}`, scopes: ['notifications:send', 'notifications:read', 'notifications:cancel'] },
 });
 expect(keyRes.status()).toBe(201);
 const key = (await keyRes.json()).key ?? (await keyRes.json()).apiKey ?? (await keyRes.json()).secret;
 expect(key).toMatch(/^ntf_/);
 return { jwt: accessToken as string, apiKey: key as string };
}

async function send(request: APIRequestContext, apiKey: string, recipient: string, extra: object = {}) {
 const res = await request.post('/api/v1/notifications', {
 headers: { 'X-API-Key': apiKey },
 data: { channel: 'EMAIL', recipient, subject: 'Delivery journey', body: 'Body text for the delivery journey.', ...extra },
 });
 expect(res.status()).toBe(202);
 const body = await res.json();
 expect(body.status).toBe('QUEUED');
 return body.id as string;
}

async function waitForStatus(request: APIRequestContext, apiKey: string, id: string, status: string, timeoutMs: number) {
 await expect
 .poll(
 async => {
 const res = await request.get(`/api/v1/notifications/${id}`, { headers: { 'X-API-Key': apiKey } });
 return (await res.json()).status;
 },
 { timeout: timeoutMs, intervals: [500, 1000, 1000, 2000] },
 )
 .toBe(status);
 const detail = await (await request.get(`/api/v1/notifications/${id}`, { headers: { 'X-API-Key': apiKey } })).json();
 const history = await (await request.get(`/api/v1/notifications/${id}/history`, { headers: { 'X-API-Key': apiKey } })).json();
 return { detail, history: history as Array<{ fromStatus: string | null; toStatus: string; actor: string }> };
}

test.describe('Email delivery journeys (isolated stack, fake provider)', => {
 test('health: liveness and readiness are UP with the worker enabled', async ({ request }) => {
 const liveness = await request.get('/actuator/health/liveness');
 expect(liveness.status()).toBe(200);
 expect((await liveness.json()).status).toBe('UP');
 const readiness = await request.get('/actuator/health/readiness');
 expect(readiness.status()).toBe(200);
 expect((await readiness.json()).status).toBe('UP');
 });

 test('accepted notification is delivered end to end (outbox -> relay -> Kafka -> worker -> provider)', async ({ request }) => {
 const { apiKey } = await signup(request);
 const id = await send(request, apiKey, 'journey.recipient@example.com');

 const { detail, history } = await waitForStatus(request, apiKey, id, 'DELIVERED', 60_000);

 expect(detail.provider).toBe('fake');
 expect(detail.attemptCount).toBe(1);
 expect(detail.completedAt).toBeTruthy();
 expect(history.map((h) => h.toStatus)).toEqual(['QUEUED', 'PROCESSING', 'DELIVERED']);
 expect(history.slice(1).every((h) => h.actor === 'SYSTEM')).toBe(true);
 });

 test('permanent provider failure ends FAILED with the provider error code', async ({ request }) => {
 const { apiKey } = await signup(request);
 const id = await send(request, apiKey, 'fail-perm@notify.local');

 const { detail, history } = await waitForStatus(request, apiKey, id, 'FAILED', 60_000);

 expect(detail.lastErrorCode).toBe('FAKE_PERMANENT_FAILURE');
 expect(detail.attemptCount).toBe(1);
 expect(history.map((h) => h.toStatus)).toEqual(['QUEUED', 'PROCESSING', 'FAILED']);
 });

 test('retryable failures walk the durable retry path through Quartz and the relay until the attempt budget is exhausted', async ({ request }) => {
 test.setTimeout(240_000);
 const { apiKey } = await signup(request);
 const id = await send(request, apiKey, 'fail-retry@notify.local');

 const { detail, history } = await waitForStatus(request, apiKey, id, 'FAILED', 200_000);

 expect(detail.lastErrorCode).toBe('FAKE_TEMPORARY_FAILURE');
 expect(detail.attemptCount).toBe(detail.maxAttempts);
 const statuses = history.map((h) => h.toStatus);
 expect(statuses.filter((s) => s === 'PROCESSING')).toHaveLength(detail.maxAttempts);
 expect(statuses.filter((s) => s === 'RETRYING')).toHaveLength(detail.maxAttempts - 1);
 // each due retry is released by the scheduler as RETRYING -> QUEUED before the next claim
 expect(history.filter((h) => h.fromStatus === 'RETRYING' && h.toStatus === 'QUEUED')).toHaveLength(detail.maxAttempts - 1);
 expect(statuses[statuses.length - 1]).toBe('FAILED');
 });
});
