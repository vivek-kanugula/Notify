import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import { getInitialTheme, THEME_STORAGE_KEY, applyThemeToDocument } from '../src/context/themeLogic.ts';

describe('Theme management and persistence', { concurrency: 1 }, () => {
  let originalWindow: any;
  let originalDocument: any;
  let originalLocalStorage: any;
  let storageMock: Record<string, string> = {};

  beforeEach(() => {
    storageMock = {};
    originalLocalStorage = (globalThis as any).localStorage;
    originalDocument = (globalThis as any).document;
    (globalThis as any).localStorage = {
      getItem: (key: string) => storageMock[key] ?? null,
      setItem: (key: string, val: string) => {
        storageMock[key] = val;
      },
      removeItem: (key: string) => {
        delete storageMock[key];
      },
      clear: () => {
        storageMock = {};
      },
    };
  });

  afterEach(() => {
    (globalThis as any).localStorage = originalLocalStorage;
    (globalThis as any).document = originalDocument;
    delete (globalThis as any).window;
  });

  test('returns light theme by default when no storage or media query exists', () => {
    (globalThis as any).window = {};
    const theme = getInitialTheme();
    assert.strictEqual(theme, 'light');
  });

  test('restores dark theme from localStorage when previously set', () => {
    storageMock[THEME_STORAGE_KEY] = 'dark';
    (globalThis as any).window = {};
    const theme = getInitialTheme();
    assert.strictEqual(theme, 'dark');
  });

  test('restores light theme from localStorage when previously set', () => {
    storageMock[THEME_STORAGE_KEY] = 'light';
    (globalThis as any).window = {};
    const theme = getInitialTheme();
    assert.strictEqual(theme, 'light');
  });

  test('falls back to dark theme when prefers-color-scheme: dark matches and storage is empty', () => {
    (globalThis as any).window = {
      matchMedia: (query: string) => ({
        matches: query === '(prefers-color-scheme: dark)',
      }),
    };
    const theme = getInitialTheme();
    assert.strictEqual(theme, 'dark');
  });

  test('prefers explicit localStorage setting over system prefers-color-scheme', () => {
    storageMock[THEME_STORAGE_KEY] = 'light';
    (globalThis as any).window = {
      matchMedia: () => ({
        matches: true, // system wants dark
      }),
    };
    const theme = getInitialTheme();
    // User choice in storage takes priority
    assert.strictEqual(theme, 'light');
  });

  test('applyThemeToDocument sets data-theme attribute on document root', () => {
    let appliedAttribute: string | null = null;
    (globalThis as any).document = {
      documentElement: {
        setAttribute: (attr: string, value: string) => {
          if (attr === 'data-theme') {
            appliedAttribute = value;
          }
        },
      },
    };

    applyThemeToDocument('dark');
    assert.strictEqual(appliedAttribute, 'dark');

    applyThemeToDocument('light');
    assert.strictEqual(appliedAttribute, 'light');
  });
});
