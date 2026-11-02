// — k6 ingress load benchmark for Notify.
//
// Measures notification acceptance throughput and latency against a running backend using the
// deterministic fake email provider. Advisory only (/): this script records numbers, it never
// gates a build. The 200 rps figure is an architectural performance goal.
//
// Usage:
// k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=<notify api key> scripts/perf/k6-notification-load.js
//
// Auth: an API key with the notifications:write scope is expected in X-API-Key. Nothing is written to
// disk by this script and no recipient data leaves the local environment.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || '';
const VUS = Number(__ENV.VUS || 200);
const DURATION = __ENV.DURATION || '60s';

const accepted = new Counter('notify_accepted_202');
const acceptedRate = new Rate('notify_accepted_rate');
const acceptLatency = new Trend('notify_accept_latency_ms', true);

export const options = {
 scenarios: {
 ingress: {
 executor: 'constant-vus',
 vus: VUS,
 duration: DURATION,
 gracefulStop: '10s',
 },
 },
 // Advisory only: thresholds are recorded in the summary but must not be treated as a build gate.
 thresholds: {},
 summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
 if (!API_KEY) {
 throw new Error('API_KEY is required: provide an API key with the notifications:write scope');
 }
 return { startedAt: new Date().toISOString() };
}

export default function {
 const payload = JSON.stringify({
 channel: 'EMAIL',
 recipient: `load-${__VU}-${__ITER}@example.com`,
 subject: 'Notify load benchmark',
 body: 'Benchmark payload. Deterministic fake provider; nothing is sent externally.',
 // Unique per iteration so the durable idempotency path stores a new row rather than replaying.
 idempotencyKey: `k6-${__VU}-${__ITER}-${Date.now()}`,
 });

 const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, {
 headers: {
 'Content-Type': 'application/json',
 'X-API-Key': API_KEY,
 },
 tags: { endpoint: 'POST /api/v1/notifications' },
 });

 const ok = res.status === 202;
 acceptedRate.add(ok);
 acceptLatency.add(res.timings.duration);
 if (ok) {
 accepted.add(1);
 }

 check(res, {
 'accepted with 202': (r) => r.status === 202,
 });
}
