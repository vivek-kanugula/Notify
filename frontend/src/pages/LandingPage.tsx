import { Link } from 'react-router-dom';
import SampleLifecycleRail from '../components/SampleLifecycleRail';
import ThemeToggle from '../components/ThemeToggle';
import { ArrowRight, ShieldCheck, Zap, Activity } from 'lucide-react';

export default function LandingPage() {
  return (
    <div className="landing-page" style={{ minHeight: '100vh', backgroundColor: 'var(--color-chalk)' }}>
      <div className="landing-container">
        {/* Navigation Bar */}
        <header className="landing-nav">
          <Link to="/" className="landing-logo">
            <div
              style={{
                width: 32,
                height: 32,
                borderRadius: 'var(--radius-control)',
                backgroundColor: 'var(--color-signal)',
                display: 'grid',
                placeItems: 'center',
                fontWeight: 800,
                color: '#FFFFFF',
                fontSize: 16,
                letterSpacing: '-0.02em',
                boxShadow: '0 2px 4px rgba(37, 99, 235, 0.25)',
              }}
            >
              N
            </div>
            <span>Notify</span>
          </Link>

          <nav className="landing-nav-links" style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Link
              to="/login"
              className="btn btn-ghost btn-sm"
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: 'var(--color-ink-2)',
                textDecoration: 'none',
              }}
            >
              Sign In
            </Link>
            <Link
              to="/signup"
              className="btn btn-primary btn-sm"
              style={{
                textDecoration: 'none',
              }}
            >
              Create Account
            </Link>
            <ThemeToggle />
          </nav>
        </header>

        {/* Hero Section */}
        <main>
          <section className="landing-hero">
            <h1 className="landing-display">
              Reliable notifications, with every delivery in view.
            </h1>
            <p className="landing-sub">
              Accepted in milliseconds, dispatched with bounded retries and cryptographic idempotency,
              with complete lifecycle visibility from queue to terminal delivery.
            </p>

            <div className="landing-cta-row">
              <Link
                to="/signup"
                className="btn btn-primary btn-lg"
                style={{
                  textDecoration: 'none',
                }}
              >
                <span>Create a tenant account</span>
                <ArrowRight size={16} aria-hidden="true" />
              </Link>
              <Link
                to="/login"
                className="btn btn-secondary btn-lg"
                style={{
                  textDecoration: 'none',
                }}
              >
                Sign In
              </Link>
            </div>

            {/* Interactive Sample Lifecycle Rail */}
            <div className="landing-rail-card">
              <SampleLifecycleRail />
            </div>
          </section>

          {/* Ruled Section 1: Reliable delivery */}
          <section className="ruled-section">
            <div className="ruled-section-grid">
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
                  <div
                    style={{
                      width: 32,
                      height: 32,
                      borderRadius: 'var(--radius-control)',
                      backgroundColor: 'var(--color-signal-subtle)',
                      color: 'var(--color-signal)',
                      display: 'grid',
                      placeItems: 'center',
                    }}
                  >
                    <Zap size={18} aria-hidden="true" />
                  </div>
                  <h2 style={{ fontSize: 22, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.02em', margin: 0 }}>
                    Reliable delivery
                  </h2>
                </div>
                <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--color-ink-2)', marginBottom: 16 }}>
                  Every notification is durably persisted to a transactional outbox before acknowledgement, guaranteeing
                  at-least-once dispatch without lost messages. Automatic exponential backoff handles transient provider
                  outages, deferring safely before exhausting attempts to Dead Letter Queues.
                </p>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <span className="badge-mono">Transactional Outbox</span>
                  <span className="badge-mono">At-Least-Once</span>
                  <span className="badge-mono">Bounded Retries</span>
                </div>
              </div>

              <div className="code-excerpt" aria-label="Idempotent notification dispatch sample">
{`POST /api/v1/notifications
Idempotency-Key: 7b9a4c-8e2d-41a3-b5f7
{
  "channel": "EMAIL",
  "recipient": "user@acme.com",
  "template": "order-shipped"
}
< HTTP/1.1 202 Accepted
< Idempotent-Replay: false`}
              </div>
            </div>
          </section>

          {/* Ruled Section 2: Isolated by design */}
          <section className="ruled-section">
            <div className="ruled-section-grid">
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
                  <div
                    style={{
                      width: 32,
                      height: 32,
                      borderRadius: 'var(--radius-control)',
                      backgroundColor: 'var(--color-st-scheduled-bg)',
                      color: 'var(--color-st-scheduled)',
                      display: 'grid',
                      placeItems: 'center',
                    }}
                  >
                    <ShieldCheck size={18} aria-hidden="true" />
                  </div>
                  <h2 style={{ fontSize: 22, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.02em', margin: 0 }}>
                    Isolated by design
                  </h2>
                </div>
                <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--color-ink-2)', marginBottom: 16 }}>
                  Multi-tenancy is enforced cryptographically and structurally at every boundary, from tenant-scoped queries
                  to in-memory HMAC verification. API keys carry bounded scopes with one-time secrets, while webhook
                  deliveries verify tenant-specific HMAC-SHA256 signatures.
                </p>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <span className="badge-mono">Row-Level Tenant Boundary</span>
                  <span className="badge-mono">HMAC-SHA256</span>
                  <span className="badge-mono">Fail-Closed 404</span>
                </div>
              </div>

              <div className="code-excerpt" aria-label="Cryptographic signature verification sample">
{`X-Notify-Delivery-ID: del_8a2f1c90-41e7
X-Notify-Signature: t=1730000000,v1=9e8b4c3d2a1f...
# Verified in constant time against tenant signing secret
# Cross-tenant queries fail closed with 404 Not Found`}
              </div>
            </div>
          </section>

          {/* Ruled Section 3: Observable */}
          <section className="ruled-section">
            <div className="ruled-section-grid">
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
                  <div
                    style={{
                      width: 32,
                      height: 32,
                      borderRadius: 'var(--radius-control)',
                      backgroundColor: 'var(--color-st-delivered-bg)',
                      color: 'var(--color-st-delivered)',
                      display: 'grid',
                      placeItems: 'center',
                    }}
                  >
                    <Activity size={18} aria-hidden="true" />
                  </div>
                  <h2 style={{ fontSize: 22, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.02em', margin: 0 }}>
                    Observable
                  </h2>
                </div>
                <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--color-ink-2)', marginBottom: 16 }}>
                  Inspect the exact trajectory of every delivery attempt in real time with our live Lifecycle Rail,
                  latency percentiles, and transition audits. Infrastructure health flows directly to Prometheus,
                  Loki, and Tempo with zero unmonitored blind spots.
                </p>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <span className="badge-mono">OpenTelemetry Spans</span>
                  <span className="badge-mono">Lifecycle Rail</span>
                  <span className="badge-mono">Audit Log</span>
                </div>
              </div>

              <div className="code-excerpt" aria-label="Observability rail trace and metrics sample">
{`[QUEUED] ──> [PROCESSING] ──> (Retry x2) ──> [DELIVERED 200 OK]
Latency: p50 18ms | p95 84ms | p99 210ms
Trace: otel-trace-id=4bf92f3577b34da6a3ce929d0e0e4736`}
              </div>
            </div>
          </section>
        </main>

        {/* Footer */}
        <footer className="landing-footer">
          <div>
            <span>© 2026 Notify Platform. Built with Java 25, Spring Boot 4, and React.</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
            <Link to="/login" className="btn btn-ghost btn-sm" style={{ textDecoration: 'none' }}>
              Sign In
            </Link>
            <Link
              to="/admin/login"
              className="btn btn-ghost btn-sm"
              style={{
                textDecoration: 'none',
                color: 'var(--color-signal)',
                fontWeight: 600,
              }}
            >
              Operator sign in →
            </Link>
          </div>
        </footer>
      </div>
    </div>
  );
}
