import { test, describe } from 'node:test';
import assert from 'node:assert';
import {
  formatExpiry,
  describeGracePeriod,
  GRACE_PERIOD_OPTIONS,
  PRESET_SEND_ONLY,
  PRESET_FULL_ACCESS,
  AVAILABLE_SCOPES,
} from '../src/pages/apiKeysLogic.ts';

describe('API Keys Grace Period and Expiry Logic', () => {
  const baseNow = new Date('2026-10-27T12:00:00.000Z').getTime();

  test('formatExpiry returns "Never" for null expiry', () => {
    assert.strictEqual(formatExpiry(null, baseNow), 'Never');
  });

  test('formatExpiry returns "Expired" for timestamps in the past', () => {
    const past = new Date('2026-10-27T11:59:59.000Z').toISOString();
    assert.strictEqual(formatExpiry(past, baseNow), 'Expired');
  });

  test('formatExpiry returns "<1h" for timestamps under an hour away', () => {
    const halfHour = new Date('2026-10-27T12:20:00.000Z').toISOString();
    assert.strictEqual(formatExpiry(halfHour, baseNow), 'Expires in <1h');
  });

  test('formatExpiry returns hours left for timestamps within 48 hours', () => {
    const twelveHours = new Date('2026-10-28T00:00:00.000Z').toISOString();
    assert.strictEqual(formatExpiry(twelveHours, baseNow), 'Expires in 12h');

    const fortyEightHours = new Date('2026-10-29T12:00:00.000Z').toISOString();
    assert.strictEqual(formatExpiry(fortyEightHours, baseNow), 'Expires in 48h');
  });

  test('formatExpiry returns formatted date string for timestamps beyond 48 hours', () => {
    const tenDays = new Date('2026-11-06T12:00:00.000Z').toISOString();
    const result = formatExpiry(tenDays, baseNow);
    assert.ok(result.includes('2026') || result.includes('11'));
  });

  test('describeGracePeriod returns accurate descriptions for standard options', () => {
    assert.strictEqual(
      describeGracePeriod(0),
      'The existing key will be revoked immediately.'
    );
    assert.strictEqual(
      describeGracePeriod(1),
      'The existing key will remain active for 1 hour to avoid downtime during service redeployments.'
    );
    assert.strictEqual(
      describeGracePeriod(24),
      'The existing key will remain active for 24 hours to avoid downtime during service redeployments.'
    );
    assert.strictEqual(
      describeGracePeriod(168),
      'The existing key will remain active for 7 days to avoid downtime during service redeployments.'
    );
  });

  test('grace period options contain the 4 standard durations (0h, 1h, 24h, 168h)', () => {
    const values = GRACE_PERIOD_OPTIONS.map((o) => o.value);
    assert.deepStrictEqual(values, [0, 1, 24, 168]);
  });

  test('scope presets configure expected sets of permissions', () => {
    assert.deepStrictEqual(PRESET_SEND_ONLY, ['notifications:send']);
    assert.strictEqual(PRESET_FULL_ACCESS.length, AVAILABLE_SCOPES.length);
    assert.ok(PRESET_FULL_ACCESS.includes('notifications:send'));
    assert.ok(PRESET_FULL_ACCESS.includes('notifications:read'));
    assert.ok(PRESET_FULL_ACCESS.includes('notifications:cancel'));
    assert.ok(PRESET_FULL_ACCESS.includes('templates:read'));
    assert.ok(PRESET_FULL_ACCESS.includes('templates:write'));
  });
});
