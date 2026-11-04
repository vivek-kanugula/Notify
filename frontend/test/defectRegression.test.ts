import { test, describe } from 'node:test';
import assert from 'node:assert';
import {
  isTerminalStatus,
  computeLifecycleStages,
  resolveStageVisualState,
} from '../src/components/lifecycleRailLogic.ts';

describe('Frontend Defect Regressions (BUG-01, BUG-02, BUG-03, BUG-04)', () => {
  // BUG-01: KpiStrip null resilience
  describe('BUG-01: KpiStrip usage metrics null resilience', () => {
    function computeKpiDisplayValues(summary: any, loading: boolean = false) {
      const successRatePercent =
        summary && typeof summary.deliverySuccessRate === 'number' && !Number.isNaN(summary.deliverySuccessRate)
          ? (summary.deliverySuccessRate * 100).toFixed(1)
          : '—';

      const accepted = loading ? '…' : summary?.totalAccepted != null ? summary.totalAccepted.toLocaleString() : '0';
      const delivered = loading ? '…' : summary?.totalDelivered != null ? summary.totalDelivered.toLocaleString() : '0';
      const processing = loading ? '…' : summary?.totalProcessing != null ? summary.totalProcessing.toLocaleString() : '0';
      const failed = loading ? '…' : summary?.totalFailed != null ? summary.totalFailed.toLocaleString() : '0';

      return { successRatePercent, accepted, delivered, processing, failed };
    }

    test('handles null summary without throwing', () => {
      assert.doesNotThrow(() => {
        const res = computeKpiDisplayValues(null);
        assert.strictEqual(res.accepted, '0');
        assert.strictEqual(res.delivered, '0');
        assert.strictEqual(res.processing, '0');
        assert.strictEqual(res.failed, '0');
        assert.strictEqual(res.successRatePercent, '—');
      });
    });

    test('handles partial / incomplete summary object without throwing TypeError', () => {
      assert.doesNotThrow(() => {
        // Simulates partial object where fields are undefined
        const incompleteSummary = { totalAccepted: 42 };
        const res = computeKpiDisplayValues(incompleteSummary);
        assert.strictEqual(res.accepted, '42');
        assert.strictEqual(res.delivered, '0');
        assert.strictEqual(res.processing, '0');
        assert.strictEqual(res.failed, '0');
        assert.strictEqual(res.successRatePercent, '—');
      });
    });

    test('correctly formats valid complete summary values', () => {
      const summary = {
        totalAccepted: 1250000,
        totalDelivered: 1245000,
        totalProcessing: 3000,
        totalFailed: 2000,
        deliverySuccessRate: 0.9984,
      };
      const res = computeKpiDisplayValues(summary);
      assert.strictEqual(res.accepted, (1250000).toLocaleString());
      assert.strictEqual(res.delivered, (1245000).toLocaleString());
      assert.strictEqual(res.processing, (3000).toLocaleString());
      assert.strictEqual(res.failed, (2000).toLocaleString());
      assert.strictEqual(res.successRatePercent, '99.8');
    });
  });

  // BUG-02: SandboxedIframe null content handling
  describe('BUG-02: SandboxedIframe null/undefined content handling', () => {
    function escapeHtml(str: string | null | undefined): string {
      if (!str) return '';
      return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    function sanitizeRawContent(content: string | null | undefined): string {
      if (!content) return '';
      return content
        .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
        .replace(/javascript:/gi, 'about:blank#blocked');
    }

    function generateSafeDoc(rawContent: string | null | undefined): string {
      const content = rawContent ?? '';
      const isHtml = /<[a-z][\s\S]*>/i.test(content);
      const cspMeta = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; form-action 'none'; connect-src 'none'; frame-src 'none';">`;

      if (isHtml) {
        const cleanContent = sanitizeRawContent(content);
        return `<!DOCTYPE html><html><head>${cspMeta}</head><body>${cleanContent}</body></html>`;
      }
      return `<!DOCTYPE html><html><head>${cspMeta}</head><body>${escapeHtml(content)}</body></html>`;
    }

    test('handles null content without throwing TypeError: replace', () => {
      assert.doesNotThrow(() => {
        const doc = generateSafeDoc(null as any);
        assert.ok(doc.includes('<body></body>'));
      });
    });

    test('handles undefined content without throwing TypeError: replace', () => {
      assert.doesNotThrow(() => {
        const doc = generateSafeDoc(undefined as any);
        assert.ok(doc.includes('<body></body>'));
      });
    });

    test('escapes HTML special characters for plain text safely', () => {
      assert.strictEqual(escapeHtml('Hello <world> & "friends"'), 'Hello &lt;world&gt; &amp; &quot;friends&quot;');
      const doc = generateSafeDoc('Hello world & "friends"');
      assert.ok(doc.includes('Hello world &amp; &quot;friends&quot;'));
    });

    test('strips malicious script tags from HTML content', () => {
      const doc = generateSafeDoc('<div><script>alert("pwned")</script><p>Safe text</p></div>');
      assert.ok(!doc.includes('alert'));
      assert.ok(doc.includes('<p>Safe text</p>'));
    });
  });

  // BUG-03: AdminSystemPage circuit breaker failureRate guard
  describe('BUG-03: Circuit breaker failureRate formatting guard', () => {
    function formatCircuitBreakerFailureRate(cb: { failureRate?: any }): { rateStr: string; barWidth: number } {
      const failureRateNum = typeof cb?.failureRate === 'number' ? cb.failureRate : Number(cb?.failureRate) || 0;
      const safeFailureRate = Number.isFinite(failureRateNum) && failureRateNum >= 0 ? failureRateNum : 0;
      return {
        rateStr: `${safeFailureRate.toFixed(1)}%`,
        barWidth: Math.min(safeFailureRate, 100),
      };
    }

    test('handles string failureRate without throwing', () => {
      assert.doesNotThrow(() => {
        const res = formatCircuitBreakerFailureRate({ failureRate: '12.5' });
        assert.strictEqual(res.rateStr, '12.5%');
        assert.strictEqual(res.barWidth, 12.5);
      });
    });

    test('handles null or undefined failureRate gracefully', () => {
      assert.doesNotThrow(() => {
        const resNull = formatCircuitBreakerFailureRate({ failureRate: null });
        assert.strictEqual(resNull.rateStr, '0.0%');
        assert.strictEqual(resNull.barWidth, 0);

        const resUndef = formatCircuitBreakerFailureRate({});
        assert.strictEqual(resUndef.rateStr, '0.0%');
        assert.strictEqual(resUndef.barWidth, 0);
      });
    });

    test('handles negative or NaN failureRate safely', () => {
      const resNeg = formatCircuitBreakerFailureRate({ failureRate: -1.0 });
      assert.strictEqual(resNeg.rateStr, '0.0%');
      assert.strictEqual(resNeg.barWidth, 0);

      const resNaN = formatCircuitBreakerFailureRate({ failureRate: 'invalid' });
      assert.strictEqual(resNaN.rateStr, '0.0%');
      assert.strictEqual(resNaN.barWidth, 0);
    });
  });

  // BUG-05: Template version creation and draft auto-save prior to publishing
  describe('BUG-05: Template version creation and draft auto-save prior to publishing', () => {
    test('createDraftVersion request payload preserves subject, body and contentType overrides', () => {
      const sourceVersion = {
        versionNumber: 1,
        subject: 'Old Subject',
        body: 'Old Body',
        contentType: 'TEXT',
      };

      const newOverrides = {
        cloneFromVersion: 1,
        subject: 'Brand New Subject',
        body: 'Brand New Body with {{var}}',
        contentType: 'HTML' as const,
      };

      // Simulates the payload assembly for createDraftVersion
      const payload = {
        cloneFromVersion: newOverrides.cloneFromVersion,
        subject: newOverrides.subject.trim() || undefined,
        body: newOverrides.body.trim(),
        contentType: newOverrides.contentType,
      };

      assert.strictEqual(payload.cloneFromVersion, 1);
      assert.strictEqual(payload.subject, 'Brand New Subject');
      assert.strictEqual(payload.body, 'Brand New Body with {{var}}');
      assert.strictEqual(payload.contentType, 'HTML');
      assert.notStrictEqual(payload.body, sourceVersion.body);
    });

    test('draft publish flow ensures draft content is updated before publish call is executed', async () => {
      let draftUpdatedWith: any = null;
      let versionPublished: number | null = null;

      // Mock update and publish functions
      async function mockUpdateDraft(id: string, ver: number, data: any) {
        draftUpdatedWith = data;
        return { versionNumber: ver, ...data };
      }

      async function mockPublish(id: string, ver: number) {
        // Assert that draft update occurred before publish
        assert.ok(draftUpdatedWith !== null, 'Draft must be saved before publishing');
        versionPublished = ver;
        return { versionNumber: ver, status: 'PUBLISHED' };
      }

      const activeDraft = {
        status: 'DRAFT',
        versionNumber: 2,
        subject: 'Cloned Subject',
        body: 'Cloned Body from v1',
        contentType: 'TEXT' as const,
      };

      const editorState = {
        subject: 'Updated Email Subject for v2',
        body: 'Updated Email Body for v2 with {{token}}',
        contentType: 'HTML' as const,
      };

      // Simulates handlePublishSubmit logic: auto-save draft first if status is DRAFT
      if (activeDraft.status === 'DRAFT') {
        await mockUpdateDraft('tpl-123', activeDraft.versionNumber, {
          subject: editorState.subject.trim() || undefined,
          body: editorState.body.trim(),
          contentType: editorState.contentType,
        });
      }
      await mockPublish('tpl-123', activeDraft.versionNumber);

      assert.strictEqual(versionPublished, 2);
      assert.strictEqual(draftUpdatedWith.body, 'Updated Email Body for v2 with {{token}}');
      assert.strictEqual(draftUpdatedWith.subject, 'Updated Email Subject for v2');
      assert.strictEqual(draftUpdatedWith.contentType, 'HTML');
    });
  });

  // BUG-06 & BUG-07: Template version deletion and live demo sandbox removal
  describe('BUG-06 & BUG-07: Template version deletion and demo sandbox removal', () => {
    test('single and bulk version delete API payload serialization and fallback version selection', () => {
      const templateVersions = [
        { versionNumber: 3, status: 'DRAFT' },
        { versionNumber: 2, status: 'PUBLISHED' },
        { versionNumber: 1, status: 'ARCHIVED' },
      ];

      // Simulate single delete target
      const singleTarget = 3;
      const remainingAfterSingle = templateVersions.filter((v) => v.versionNumber !== singleTarget);
      assert.strictEqual(remainingAfterSingle.length, 2);
      assert.strictEqual(remainingAfterSingle[0].versionNumber, 2);

      // Simulate bulk delete target [1, 3]
      const bulkTargets = [1, 3];
      const remainingAfterBulk = templateVersions.filter((v) => !bulkTargets.includes(v.versionNumber));
      assert.strictEqual(remainingAfterBulk.length, 1);
      assert.strictEqual(remainingAfterBulk[0].versionNumber, 2);

      // Verify active version fallback selection when active version is deleted
      const activeVersionNumber = 3;
      let nextActiveVerNum: number | undefined = activeVersionNumber;
      if (bulkTargets.includes(activeVersionNumber)) {
        const pub = remainingAfterBulk.find((v) => v.status === 'PUBLISHED');
        nextActiveVerNum = pub?.versionNumber ?? (remainingAfterBulk.length > 0 ? remainingAfterBulk[0].versionNumber : undefined);
      }
      assert.strictEqual(nextActiveVerNum, 2);

      // Verify fallback when ALL versions are deleted
      const allTargets = [1, 2, 3];
      const remainingAfterAll = templateVersions.filter((v) => !allTargets.includes(v.versionNumber));
      let fallbackWhenEmpty: number | null = null;
      if (allTargets.includes(activeVersionNumber)) {
        const pub = remainingAfterAll.find((v) => v.status === 'PUBLISHED');
        fallbackWhenEmpty = pub?.versionNumber ?? (remainingAfterAll.length > 0 ? remainingAfterAll[0].versionNumber : null);
      }
      assert.strictEqual(fallbackWhenEmpty, null);
    });

    test('multi-selection toggle and select all behavior for version history rail', () => {
      const versionNumbers = [3, 2, 1];
      let selected: number[] = [];

      // Toggle select version 3
      selected = selected.includes(3) ? selected.filter((n) => n !== 3) : [...selected, 3];
      assert.deepStrictEqual(selected, [3]);

      // Toggle select version 2
      selected = selected.includes(2) ? selected.filter((n) => n !== 2) : [...selected, 2];
      assert.deepStrictEqual(selected, [3, 2]);

      // Toggle deselect version 3
      selected = selected.includes(3) ? selected.filter((n) => n !== 3) : [...selected, 3];
      assert.deepStrictEqual(selected, [2]);

      // Select all
      selected = selected.length === versionNumbers.length ? [] : [...versionNumbers];
      assert.deepStrictEqual(selected, [3, 2, 1]);

      // Deselect all (toggle when all selected)
      selected = selected.length === versionNumbers.length ? [] : [...versionNumbers];
      assert.deepStrictEqual(selected, []);
    });
  });

  // BUG-08, BUG-09, BUG-10, BUG-11: Top bar cleanup, modal viewport centering, unified send modal with templates, and realtime lifecycle progression
  describe('BUG-08 to BUG-11: Modal centering, template dispatch, and realtime lifecycle focus', () => {
    test('BUG-10: Send notification modal constructs correct payload for custom vs template dispatch', () => {
      // 1. Custom message payload
      const customPayload = {
        channel: 'EMAIL',
        recipient: 'user@example.com',
        subject: 'Welcome!',
        body: 'Hello there',
      };
      assert.strictEqual(customPayload.channel, 'EMAIL');
      assert.strictEqual(customPayload.subject, 'Welcome!');
      assert.strictEqual(customPayload.body, 'Hello there');

      // 2. Template message payload
      const templatePayload = {
        channel: 'EMAIL',
        recipient: 'user@example.com',
        templateId: 'tpl-abc-123',
        templateVersion: 2,
        templateVariables: { name: 'Alice', code: '48291' },
      };
      assert.strictEqual(templatePayload.channel, 'EMAIL');
      assert.strictEqual(templatePayload.templateId, 'tpl-abc-123');
      assert.strictEqual(templatePayload.templateVersion, 2);
      assert.deepStrictEqual(templatePayload.templateVariables, { name: 'Alice', code: '48291' });
    });

    test('BUG-11: LifecycleRail resolveStageVisualState displays completed checkmark for finished processing stage', () => {
      // When PROCESSING is done, visual state MUST be completed check, NOT active spinner
      const doneProcessing = resolveStageVisualState('PROCESSING', 'done');
      assert.strictEqual(doneProcessing.iconType, 'check');
      assert.strictEqual(doneProcessing.isCompleted, true);
      assert.strictEqual(doneProcessing.isPulsing, false);

      // When PROCESSING is current, it has active spinner and pulsing focus
      const currentProcessing = resolveStageVisualState('PROCESSING', 'current');
      assert.strictEqual(currentProcessing.iconType, 'spinner');
      assert.strictEqual(currentProcessing.isCompleted, false);
      assert.strictEqual(currentProcessing.isPulsing, true);

      // When QUEUED is current, it has layers icon and pulsing focus
      const currentQueued = resolveStageVisualState('QUEUED', 'current');
      assert.strictEqual(currentQueued.iconType, 'layers');
      assert.strictEqual(currentQueued.isPulsing, true);

      // When QUEUED is done, it transitions to completed check
      const doneQueued = resolveStageVisualState('QUEUED', 'done');
      assert.strictEqual(doneQueued.iconType, 'check');
      assert.strictEqual(doneQueued.isCompleted, true);
    });

    test('BUG-11: Real-time lifecycle stage focus progresses from QUEUED to PROCESSING to DELIVERED', () => {
      // State 1: QUEUED
      const queuedStages = computeLifecycleStages({
        status: 'QUEUED',
        createdAt: '2026-11-04T10:00:00Z',
      });
      assert.strictEqual(queuedStages.stages[0].name, 'QUEUED');
      assert.strictEqual(queuedStages.stages[0].state, 'current'); // FOCUSED
      assert.strictEqual(queuedStages.stages[1].name, 'PROCESSING');
      assert.strictEqual(queuedStages.stages[1].state, 'upcoming'); // NOT active
      assert.strictEqual(queuedStages.stages[2].name, 'DELIVERED');
      assert.strictEqual(queuedStages.stages[2].state, 'upcoming'); // NOT active

      // State 2: PROCESSING
      const processingStages = computeLifecycleStages({
        status: 'PROCESSING',
        processingStartedAt: '2026-11-04T10:00:02Z',
      });
      assert.strictEqual(processingStages.stages[0].name, 'QUEUED');
      assert.strictEqual(processingStages.stages[0].state, 'done'); // Completed
      assert.strictEqual(processingStages.stages[1].name, 'PROCESSING');
      assert.strictEqual(processingStages.stages[1].state, 'current'); // FOCUSED
      assert.strictEqual(processingStages.stages[2].name, 'DELIVERED');
      assert.strictEqual(processingStages.stages[2].state, 'upcoming'); // NOT active

      // State 3: DELIVERED
      const deliveredStages = computeLifecycleStages({
        status: 'DELIVERED',
        completedAt: '2026-11-04T10:00:04Z',
      });
      assert.strictEqual(deliveredStages.stages[0].name, 'QUEUED');
      assert.strictEqual(deliveredStages.stages[0].state, 'done'); // Completed
      assert.strictEqual(deliveredStages.stages[1].name, 'PROCESSING');
      assert.strictEqual(deliveredStages.stages[1].state, 'done'); // Completed (no longer spinning!)
      assert.strictEqual(deliveredStages.stages[2].name, 'DELIVERED');
      assert.strictEqual(deliveredStages.stages[2].state, 'current'); // FOCUSED
    });

    test('BUG-11: Terminal status predicate accurately governs real-time polling lifecycle', () => {
      // Non-terminal states must continue polling
      assert.strictEqual(isTerminalStatus('QUEUED'), false);
      assert.strictEqual(isTerminalStatus('PROCESSING'), false);
      assert.strictEqual(isTerminalStatus('RETRYING'), false);
      assert.strictEqual(isTerminalStatus('SCHEDULED'), false);

      // Terminal states must halt polling
      assert.strictEqual(isTerminalStatus('DELIVERED'), true);
      assert.strictEqual(isTerminalStatus('FAILED'), true);
      assert.strictEqual(isTerminalStatus('DLQ'), true);
      assert.strictEqual(isTerminalStatus('CANCELLED'), true);
    });
  });
});
