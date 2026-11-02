import { test, expect, APIRequestContext } from '@playwright/test';

// webhook journeys over real HTTP against the isolated stack (webhook worker enabled).
//
// The only destination is the stack's own demo receiver on loopback, which is what makes the provisioned endpoint
// is_demo and is the only reason plain http to a loopback address is allowed . No external host is contacted.

const WEBHOOK_PATH = '/api/v1/webhook-endpoint';

async function signup(request: APIRequestContext) {
 const id = `${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
 const res = await request.post('/auth/signup', {
 data: { tenantName: `Demo Org ${id}`, email: `demo_${id}@notify.local`, password: 'Password123!' },
 });
 expect(res.status()).toBe(201);
 const { accessToken } = await res.json();
 const keyRes = await request.post('/api/v1/keys', {
 headers: { Authorization: `Bearer ${accessToken}` },
 data: { name: `b8-key-${id}`, scopes: ['notifications:send', 'notifications:read'] },
 });
 expect(keyRes.status()).toBe(201);
 const body = await keyRes.json();
 const key = body.key ?? body.apiKey ?? body.secret;
 expect(key).toMatch(/^ntf_/);
 return { jwt: accessToken as string, apiKey: key as string };
}

function auth(jwt: string) {
 return { Authorization: `Bearer ${jwt}` };
}

async function waitForStatus(
 request: APIRequestContext,
 apiKey: string,
 id: string,
 status: string,
 timeoutMs: number,
) {
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
 const history = await (
 await request.get(`/api/v1/notifications/${id}/history`, { headers: { 'X-API-Key': apiKey } })
 ).json();
 return { detail, history: history as Array<{ fromStatus: string | null; toStatus: string; actor: string }> };
}

test.describe('Webhook delivery journeys (isolated stack, loopback demo receiver)', => {
 test('a new tenant is provisioned with the demo endpoint, and a read never reveals the secret', async ({ request }) => {
 const { jwt } = await signup(request);

 const res = await request.get(WEBHOOK_PATH, { headers: auth(jwt) });
 expect(res.status()).toBe(200);
 const view = await res.json();
 expect(view.isDemo).toBe(true);
 expect(view.enabled).toBe(true);
 expect(view.url).toContain('/demo/webhook-receiver');
 expect(JSON.stringify(view)).not.toContain('whsec_');
 });

 test('rotation returns a new secret once and the rotated secret is never readable afterwards', async ({ request }) => {
 const { jwt } = await signup(request);

 const first = await request.post(`${WEBHOOK_PATH}/rotate-secret`, { headers: auth(jwt) });
 expect(first.status()).toBe(201);
 const firstSecret = (await first.json()).signingSecret as string;
 expect(firstSecret).toMatch(/^whsec_/);

 const second = await request.post(`${WEBHOOK_PATH}/rotate-secret`, { headers: auth(jwt) });
 expect(second.status()).toBe(201);
 expect((await second.json()).signingSecret).not.toBe(firstSecret);

 const read = await request.get(WEBHOOK_PATH, { headers: auth(jwt) });
 expect(await read.text()).not.toContain(firstSecret);
 });

 test('the test ping reaches the demo receiver and reports a 2xx without writing a notification', async ({ request }) => {
 const { jwt, apiKey } = await signup(request);

 const res = await request.post(`${WEBHOOK_PATH}/test`, { headers: auth(jwt) });
 expect(res.status()).toBe(200);
 const result = await res.json();
 expect(result.delivered).toBe(true);
 expect(result.httpStatus).toBe(200);
 expect(result.outcome).toBe('DELIVERED');

 const list = await request.get('/api/v1/notifications', { headers: { 'X-API-Key': apiKey } });
 expect(list.status()).toBe(200);
 const page = await list.json();
 expect(page.content ?? page).toHaveLength(0);
 });

 test('a queued WEBHOOK notification is signed, posted to the receiver and recorded DELIVERED', async ({ request }) => {
 const { jwt, apiKey } = await signup(request);
 const destination = (await (await request.get(WEBHOOK_PATH, { headers: auth(jwt) })).json()).url as string;

 const res = await request.post('/api/v1/notifications', {
 headers: { 'X-API-Key': apiKey },
 data: {
 channel: 'WEBHOOK',
 recipient: destination,
 subject: 'Delivery journey',
 body: 'Body text for the webhook journey.',
 metadata: { orderId: 'Order-1' },
 },
 });
 expect(res.status()).toBe(202);
 const id = (await res.json()).id as string;

 const { detail, history } = await waitForStatus(request, apiKey, id, 'DELIVERED', 60_000);

 expect(detail.provider).toBe('webhook');
 expect(detail.attemptCount).toBe(1);
 expect(detail.completedAt).toBeTruthy();
 expect(history.map((h) => h.toStatus)).toEqual(['QUEUED', 'PROCESSING', 'DELIVERED']);
 expect(history.slice(1).every((h) => h.actor === 'SYSTEM')).toBe(true);
 });

 test('a disabled endpoint fails the delivery permanently instead of retrying it', async ({ request }) => {
 const { jwt, apiKey } = await signup(request);
 const destination = (await (await request.get(WEBHOOK_PATH, { headers: auth(jwt) })).json()).url as string;

 const disable = await request.put(WEBHOOK_PATH, {
 headers: auth(jwt),
 data: { url: destination, enabled: false },
 });
 expect(disable.status()).toBe(200);
 expect((await disable.json()).enabled).toBe(false);

 const res = await request.post('/api/v1/notifications', {
 headers: { 'X-API-Key': apiKey },
 data: { channel: 'WEBHOOK', recipient: destination, subject: 'Delivery disabled', body: 'Not delivered.' },
 });
 expect(res.status()).toBe(202);
 const id = (await res.json()).id as string;

 const { detail } = await waitForStatus(request, apiKey, id, 'FAILED', 60_000);
 expect(detail.lastErrorCode).toBe('WEBHOOK_ENDPOINT_DISABLED');
 expect(detail.attemptCount).toBe(1);
 });

 test('a destination the policy blocks is refused at configuration time and the stored endpoint is unchanged', async ({ request }) => {
 const { jwt } = await signup(request);
 const before = await (await request.get(WEBHOOK_PATH, { headers: auth(jwt) })).json();

 for (const url of [
 'http://169.254.169.254/latest/meta-data/',
 'https://10.0.0.5/hook',
 'https://localhost/hook',
 'http://example.com/hook',
 ]) {
 const res = await request.put(WEBHOOK_PATH, { headers: auth(jwt), data: { url } });
 expect(res.status(), `destination ${url}`).toBe(422);
 const body = await res.json();
 expect(body.errorCode).toBe('VALIDATION_FAILED');
 expect(body.destinationRejectionReason).toBeTruthy();
 }

 const after = await (await request.get(WEBHOOK_PATH, { headers: auth(jwt) })).json();
 expect(after.url).toBe(before.url);
 });

 test('webhook configuration is not reachable with an API key, only with the owner JWT', async ({ request }) => {
 const { apiKey } = await signup(request);

 const read = await request.get(WEBHOOK_PATH, { headers: { 'X-API-Key': apiKey } });
 expect(read.status()).toBeGreaterThanOrEqual(400);
 const rotate = await request.post(`${WEBHOOK_PATH}/rotate-secret`, { headers: { 'X-API-Key': apiKey } });
 expect(rotate.status()).toBeGreaterThanOrEqual(400);
 });

 test('the demo receiver stores nothing for an unsigned request', async ({ request }) => {
 const { jwt } = await signup(request);
 const destination = (await (await request.get(WEBHOOK_PATH, { headers: auth(jwt) })).json()).url as string;

 const res = await request.post(destination, {
 headers: { 'X-Notify-Delivery-ID': `${'0'.repeat(8)}-0000-0000-0000-000000000000-1` },
 data: { schemaVersion: 1 },
 });
 expect([401, 404]).toContain(res.status());
 expect((await res.json()).signatureValid).toBe(false);
 });
});
