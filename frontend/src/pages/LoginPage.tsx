import { useState, useRef, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import ThemeToggle from '../components/ThemeToggle';

/**
 * Tenant login page — password login and signup.
 */
export default function LoginPage({ initialSignup = false }: { initialSignup?: boolean }) {
  const { login, signup } = useAuth();
  const [isSignup, setIsSignup] = useState(() => {
    if (initialSignup) return true;
    if (typeof window !== 'undefined' && window.location.pathname === '/signup') return true;
    return false;
  });
  const [tenantName, setTenantName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const errorRef = useRef<HTMLDivElement>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setSubmitting(true);

    try {
      if (isSignup) {
        await signup(tenantName, email, password);
      } else {
        await login(email, password);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-container">
      <div style={{ position: 'absolute', top: 20, right: 24, zIndex: 10 }}>
        <ThemeToggle />
      </div>

      <div className="login-card">
        <div className="login-header">
          <Link
            to="/"
            style={{
              width: 36,
              height: 36,
              borderRadius: 'var(--radius-control)',
              backgroundColor: 'var(--color-signal)',
              display: 'grid',
              placeItems: 'center',
              fontWeight: 800,
              color: '#FFFFFF',
              fontSize: 18,
              textDecoration: 'none',
              letterSpacing: '-0.02em',
              boxShadow: '0 2px 6px rgba(37, 99, 235, 0.3)',
            }}
          >
            N
          </Link>
          <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', color: 'var(--color-harbor)' }}>
            {isSignup ? 'Create Account' : 'Sign In'}
          </h1>
          <p style={{ color: 'var(--color-ink-2)', fontSize: 13 }}>
            {isSignup ? 'Set up your Notify tenant workspace' : 'Access your Notify tenant dashboard'}
          </p>
        </div>

        {error && (
          <div
            ref={errorRef}
            tabIndex={-1}
            role="alert"
            className="login-error"
            style={{ marginBottom: 16 }}
          >
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="login-form">
          {isSignup && (
            <div className="form-group">
              <label htmlFor="tenantName">Organization Name</label>
              <input
                id="tenantName"
                type="text"
                value={tenantName}
                onChange={e => setTenantName(e.target.value)}
                placeholder="Acme Corp"
                required
                autoComplete="organization"
              />
            </div>
          )}

          <div className="form-group">
            <label htmlFor="email">Work Email</label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
              autoComplete="email"
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder={isSignup ? 'Min. 8 characters' : '••••••••'}
              required
              minLength={isSignup ? 8 : undefined}
              autoComplete={isSignup ? 'new-password' : 'current-password'}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            style={{ width: '100%', padding: '10px 16px', marginTop: 6 }}
            disabled={submitting}
          >
            {submitting ? 'Please wait…' : (isSignup ? 'Create Account' : 'Sign In')}
          </button>
        </form>

        <div className="login-footer">
          <button
            type="button"
            className="login-toggle"
            onClick={() => { setIsSignup(!isSignup); setError(''); }}
          >
            {isSignup ? 'Already have an account? Sign in' : "Don't have an account? Sign up"}
          </button>
          <div style={{ marginTop: 14 }}>
            <Link
              to="/admin/login"
              style={{
                fontSize: 12,
                color: 'var(--color-ink-3)',
                textDecoration: 'none',
                fontWeight: 500,
              }}
            >
              Operator Sign In →
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
