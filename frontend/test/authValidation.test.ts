import { test, describe } from 'node:test';
import assert from 'node:assert';
import { isGoogleEmail } from '../src/utils/authValidation.ts';

describe('Authentication Email Validation (@gmail.com Google Domain)', () => {
  test('accepts standard @gmail.com addresses', () => {
    assert.strictEqual(isGoogleEmail('user@gmail.com'), true);
    assert.strictEqual(isGoogleEmail('john.doe@gmail.com'), true);
    assert.strictEqual(isGoogleEmail('developer+test@gmail.com'), true);
    assert.strictEqual(isGoogleEmail('notify_service-2026@gmail.com'), true);
  });

  test('accepts uppercase and mixed-case @gmail.com addresses', () => {
    assert.strictEqual(isGoogleEmail('USER@GMAIL.COM'), true);
    assert.strictEqual(isGoogleEmail('John.Doe@Gmail.Com'), true);
    assert.strictEqual(isGoogleEmail('Test@GMAIL.com'), true);
  });

  test('trims surrounding whitespace from email', () => {
    assert.strictEqual(isGoogleEmail('  user@gmail.com  '), true);
    assert.strictEqual(isGoogleEmail('\tdeveloper@gmail.com\n'), true);
  });

  test('rejects non-Google email domains', () => {
    assert.strictEqual(isGoogleEmail('user@yahoo.com'), false);
    assert.strictEqual(isGoogleEmail('user@outlook.com'), false);
    assert.strictEqual(isGoogleEmail('user@hotmail.com'), false);
    assert.strictEqual(isGoogleEmail('user@acme.org'), false);
    assert.strictEqual(isGoogleEmail('user@company.io'), false);
    assert.strictEqual(isGoogleEmail('user@google.com'), false);
  });

  test('rejects subdomain or pseudo-gmail extensions', () => {
    assert.strictEqual(isGoogleEmail('user@gmail.com.co'), false);
    assert.strictEqual(isGoogleEmail('user@gmail.org'), false);
    assert.strictEqual(isGoogleEmail('user@notgmail.com'), false);
    assert.strictEqual(isGoogleEmail('user@fakegmail.com'), false);
    assert.strictEqual(isGoogleEmail('user@gmail.net'), false);
  });

  test('rejects malformed emails and invalid syntax', () => {
    assert.strictEqual(isGoogleEmail(''), false);
    assert.strictEqual(isGoogleEmail('   '), false);
    assert.strictEqual(isGoogleEmail('plainaddress'), false);
    assert.strictEqual(isGoogleEmail('@gmail.com'), false);
    assert.strictEqual(isGoogleEmail('user@'), false);
    assert.strictEqual(isGoogleEmail('user@gmail'), false);
    assert.strictEqual(isGoogleEmail('user @gmail.com'), false);
  });
});
