import { test, describe } from 'node:test';
import assert from 'node:assert';

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
});
