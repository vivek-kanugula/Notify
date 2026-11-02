import { test, expect, APIRequestContext } from '@playwright/test';

// in-app journeys over real HTTP against the isolated stack (in-app worker enabled).
//
// Everything here is local to this run: an in-app delivery is a row in the run's own disposable PostgreSQL plus a
// wake-up hint on its own disposable Redis. No external host, no provider, no key.

const INBOX = '/api/v1/inapp-messages';
const TOKENS = '/api/v1/inapp/stream-tokens';

async function signup(request: APIRequestContext) {
 const id = `${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
 const res = await request.post('/auth/signup', {
 data: { tenantName: `Demo Org ${id}`, email: `demo_${id}@notify.local`, password: 'Password123!' },
 });
 expect(res.status()).toBe(201);
 const { accessToken } = await res.json();
 const keyRes = await request.post('/api/v1/keys', {
 headers: { Authorization: `Bearer ${accessToken}` },
 data: {
 name: `b9-key-${id}`,
 scopes: ['notifications:send', 'notifications:read', 'inapp:stream'],
 },
 });
 expect(keyRes.status()).toBe(201);
 const body = await keyRes.json();
 const key = body.key ?? body.apiKey ?? body.secret;
 expect(key).toMatch(/^ntf_/);
 return { jwt: accessToken as string, apiKey: key as string, recipient: `user-${id}` };
}

function auth(jwt: string) {
 return { Authorization: `Bearer ${jwt}` };
}

async function waitForStatus(
 request: APIRequestContext,
 apiKey: string,
 id: string,
 status: string,
 timeoutMs = 60000,
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
}

async function send(request: APIRequestContext, apiKey: string, recipient: string, subject: string) {
 const res = await request.post('/api/v1/notifications', {
 headers: { 'X-API-Key': apiKey },
 data: { channel: 'IN_APP', recipient, subject, body: `Body of ${subject}` },
 });
 expect(res.status()).toBe(202);
 return (await res.json()).id as string;
}

/**
 * Reads an SSE connection for up to {@code windowMs} and returns the raw frames.
 *
 * Playwright's APIRequestContext buffers a whole response, which never arrives for a stream, so this uses the
 * runner's own `fetch` and reads the body incrementally. The URL is the isolated stack's loopback base, which
 * `playwright.delivery.config.ts` has already validated.
 */
async function readStream(token: string, lastEventId: string, windowMs: number): Promise<string> {
 const base = process.env.NOTIFY_E2E_BASE_URL!;
 const url = `${base}/sse/inapp?token=${encodeURIComponent(token)}&lastEventId=${encodeURIComponent(lastEventId)}`;
 const abort = new AbortController();
 const stop = setTimeout(() => abort.abort(), windowMs);
 let text = '';
 try {
 const response = await fetch(url, { signal: abort.signal, headers: { Accept: 'text/event-stream' } });
 expect(response.status).toBe(200);
 const reader = response.body!.getReader();
 const decoder = new TextDecoder();
 for () {
 const { done, value } = await reader.read();
 if (done) break;
 text += decoder.decode(value, { stream: true });
 }
 } catch (error) {
 if (!(error instanceof Error) || error.name !== 'AbortError') throw error;
 } finally {
 clearTimeout(stop);
 }
 return text;
}

test.describe('In-app delivery journeys (isolated stack)', => {
 test('an accepted IN_APP notification is delivered and appears in the saved inbox', async ({ request }) => {
 const { jwt, apiKey, recipient } = await signup(request);

 const id = await send(request, apiKey, recipient, 'Order shipped');
 await waitForStatus(request, apiKey, id, 'DELIVERED');

 const inbox = await request.get(`${INBOX}?recipientRef=${encodeURIComponent(recipient)}`, {
 headers: auth(jwt),
 });
 expect(inbox.status()).toBe(200);
 const page = await inbox.json();
 expect(page.content).toHaveLength(1);
 expect(page.content[0].notificationId).toBe(id);
 expect(page.content[0].subject).toBe('Order shipped');
 expect(page.content[0].read).toBe(false);
 expect(page.unread).toBe(1);
 // The cursor is a string, so a 64-bit seq survives a browser intact.
 expect(typeof page.content[0].seq).toBe('string');
 });

 test('DELIVERED means persisted: the attempt is recorded against the inapp provider', async ({ request }) => {
 const { apiKey, recipient } = await signup(request);

 const id = await send(request, apiKey, recipient, 'Persisted');
 await waitForStatus(request, apiKey, id, 'DELIVERED');

 const detail = await (
 await request.get(`/api/v1/notifications/${id}`, { headers: { 'X-API-Key': apiKey } })
 ).json();
 expect(detail.provider).toBe('inapp');
 expect(detail.attemptCount).toBe(1);
 });

 test('messages are ordered by cursor and a recipient only sees its own', async ({ request }) => {
 const { jwt, apiKey, recipient } = await signup(request);
 const other = `${recipient}-other`;

 const first = await send(request, apiKey, recipient, 'First');
 const second = await send(request, apiKey, recipient, 'Second');
 const foreign = await send(request, apiKey, other, 'Not yours');
 for (const id of [first, second, foreign]) {
 await waitForStatus(request, apiKey, id, 'DELIVERED');
 }

 const mine = await (
 await request.get(`${INBOX}?recipientRef=${encodeURIComponent(recipient)}`, { headers: auth(jwt) })
 ).json();
 expect(mine.content.map((m: { subject: string }) => m.subject)).toEqual(['Second', 'First']);

 const theirs = await (
 await request.get(`${INBOX}?recipientRef=${encodeURIComponent(other)}`, { headers: auth(jwt) })
 ).json();
 expect(theirs.content).toHaveLength(1);
 expect(theirs.content[0].subject).toBe('Not yours');
 });

 test('marking read is idempotent and the unread count follows', async ({ request }) => {
 const { jwt, apiKey, recipient } = await signup(request);
 const id = await send(request, apiKey, recipient, 'Read me');
 await waitForStatus(request, apiKey, id, 'DELIVERED');

 const page = await (
 await request.get(`${INBOX}?recipientRef=${encodeURIComponent(recipient)}`, { headers: auth(jwt) })
 ).json();
 const seq = page.content[0].seq as string;

 for (const expected of [204, 204]) {
 const res = await request.post(`${INBOX}/${seq}/read?recipientRef=${encodeURIComponent(recipient)}`, {
 headers: auth(jwt),
 });
 expect(res.status()).toBe(expected);
 }

 const after = await (
 await request.get(`${INBOX}?recipientRef=${encodeURIComponent(recipient)}`, { headers: auth(jwt) })
 ).json();
 expect(after.unread).toBe(0);
 expect(after.content[0].read).toBe(true);
 });

 test('an invalid cursor is 422 VALIDATION_FAILED, with no new error code', async ({ request }) => {
 const { jwt, recipient } = await signup(request);

 for (const bad of ['abc', '-1']) {
 const res = await request.get(
 `${INBOX}?recipientRef=${encodeURIComponent(recipient)}&after=${encodeURIComponent(bad)}`,
 { headers: auth(jwt) },
 );
 expect(res.status()).toBe(422);
 expect((await res.json()).title).toBe('VALIDATION_FAILED');
 }
 });

 test('a stream token is minted for a recipient of the caller tenant, and an API key needs inapp:stream', async ({
 request,
 }) => {
 const { jwt, apiKey, recipient } = await signup(request);

 const minted = await request.post(TOKENS, { headers: auth(jwt), data: { recipientRef: recipient } });
 expect(minted.status()).toBe(200);
 const token = await minted.json();
 expect(typeof token.token).toBe('string');
 expect(token.streamUrl).toBe('/sse/inapp');
 expect(token.expiresInSeconds).toBe(60);

 const viaKey = await request.post(TOKENS, {
 headers: { 'X-API-Key': apiKey },
 data: { recipientRef: recipient },
 });
 expect(viaKey.status()).toBe(200);
 });

 test('a stream token is single use: the second connection with it is rejected', async ({ request }) => {
 const { jwt, recipient } = await signup(request);
 const token = (
 await (await request.post(TOKENS, { headers: auth(jwt), data: { recipientRef: recipient } })).json()
 ).token as string;

 // The first connection consumes the token. The stream stays open, so the request is aborted deliberately
 // after the headers arrive; what matters is that the token is gone afterwards.
 const first = await request.get(`/sse/inapp?token=${encodeURIComponent(token)}`, { timeout: 3000 }).catch(
 (error) => error,
 );
 expect(first).toBeTruthy();

 const second = await request.get(`/sse/inapp?token=${encodeURIComponent(token)}`);
 expect(second.status()).toBe(401);
 expect((await second.json()).title).toBe('STREAM_TOKEN_INVALID');
 });

 test('an unknown token never opens a stream', async ({ request }) => {
 const res = await request.get('/sse/inapp?token=definitely-not-a-token');
 expect(res.status()).toBe(401);
 expect((await res.json()).title).toBe('STREAM_TOKEN_INVALID');
 });

 test('F3 a backlog over the catch-up budget resyncs, and the paginated recovery skips nothing', async ({
 request,
 }) => {
 test.setTimeout(300_000);
 const { jwt, apiKey, recipient } = await signup(request);

 // 0. The first admission for a brand-new tenant arms the ≈95 s provenance barrier (an accepted cost),
 // so it is REFUSED - and that refusal must be the documented 429 with a Retry-After, not a 500. Until this
 // cycle it was a 500: the mapping declared `produces = text/event-stream`, which made the problem body
 // unwritable. Arming the barrier here also starts its clock, which the sends below then run inside.
 const barrierStart = Date.now();
 const firstToken = (
 await (await request.post(TOKENS, { headers: auth(jwt), data: { recipientRef: recipient } })).json()
 ).token as string;
 const refused = await request.get(`/sse/inapp?token=${encodeURIComponent(firstToken)}`);
 expect(refused.status(), 'a refused stream is 429, never 500').toBe(429);
 expect(Number(refused.headers()['retry-after'])).toBeGreaterThan(0);
 const problem = await refused.json();
 expect(problem.title).toBe('STREAM_LIMIT_REACHED');
 expect(problem.retryAfterSeconds).toBeGreaterThan(0);

 // 101 delivered messages: one more than the contracted catch-up budget of 100.
 const BACKLOG = 101;
 for (let i = 0; i < BACKLOG; i++) {
 await send(request, apiKey, recipient, `Backlog ${i}`);
 }
 await expect
 .poll(
 async => {
 const page = await (
 await request.get(`${INBOX}?recipientRef=${encodeURIComponent(recipient)}&size=1`, {
 headers: auth(jwt),
 })
 ).json();
 return page.unread;
 },
 { timeout: 180_000, intervals: [1000, 2000, 2000, 5000] },
 )
 .toBe(BACKLOG);

 // 1. Wait out whatever is left of the barrier the first attempt armed, then open below the backlog: the stream
 // is told to resync and given the cursor it must recover from.
 const barrierRemaining = 97_000 - (Date.now() - barrierStart);
 if (barrierRemaining > 0) {
 await new Promise((resolve) => setTimeout(resolve, barrierRemaining));
 }
 const token = (
 await (await request.post(TOKENS, { headers: auth(jwt), data: { recipientRef: recipient } })).json()
 ).token as string;
 const firstFrames = await readStream(token, '0', 20_000);
 expect(firstFrames).toContain('event:resync');
 expect(firstFrames).toContain('GAP_TOO_LARGE');
 expect(firstFrames, 'no row is sent before the client has caught up').not.toContain('event:message');

 // 2. The recovery: the approved paginated read, newest first, until it reaches the stream's cursor of 0.
 const seqs: string[] = [];
 let after: string | null = null;
 let hasMore = true;
 let pages = 0;
 while (hasMore && pages < 20) {
 const url = `${INBOX}?recipientRef=${encodeURIComponent(recipient)}&size=25${
 after ? `&after=${encodeURIComponent(after)}` : ''
 }`;
 const page = await (await request.get(url, { headers: auth(jwt) })).json();
 page.content.forEach((m: { seq: string }) => seqs.push(m.seq));
 hasMore = page.page.hasMore;
 after = page.nextCursor;
 pages++;
 }
 expect(seqs, 'every eligible message was reachable by pagination').toHaveLength(BACKLOG);
 expect(new Set(seqs).size, 'and none was returned twice').toBe(BACKLOG);
 const recoveryCursor = seqs.reduce((best, seq) => (BigInt(seq) > BigInt(best) ? seq : best), seqs[0]);

 // 3. Reconnecting at the recovered cursor resolves the condition rather than reproducing it, and a message
 // delivered afterwards arrives live.
 const secondToken = (
 await (await request.post(TOKENS, { headers: auth(jwt), data: { recipientRef: recipient } })).json()
 ).token as string;
 const liveFrames = readStream(secondToken, recoveryCursor, 12_000);
 await new Promise((resolve) => setTimeout(resolve, 2000));
 await send(request, apiKey, recipient, 'After the handover');
 const frames = await liveFrames;

 expect(frames, 'the loop is broken: no second resync at the recovered cursor').not.toContain('event:resync');
 expect(frames).toContain('event:message');
 expect(frames).toContain('After the handover');
 });

 test('the saved inbox needs no stream and no token at all', async ({ request }) => {
 const { jwt, apiKey, recipient } = await signup(request);
 const id = await send(request, apiKey, recipient, 'Readable without a stream');
 await waitForStatus(request, apiKey, id, 'DELIVERED');

 // No token was ever minted for this recipient; the persisted inbox is an ordinary authenticated read.
 const inbox = await request.get(`${INBOX}?recipientRef=${encodeURIComponent(recipient)}`, {
 headers: auth(jwt),
 });
 expect(inbox.status()).toBe(200);
 expect((await inbox.json()).content).toHaveLength(1);
 });
});
