import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAdminAuth } from '../../auth/AdminAuthContext';
import ThemeToggle from '../../components/ThemeToggle';
import { ShieldAlert, Loader2, ArrowLeft, Eye, EyeOff } from 'lucide-react';

export default function AdminLoginPage() {
  const { login } = useAdminAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      await login(email, password);
      navigate('/admin/tenants', { replace: true });
    } catch (err: any) {
      setError(err.message || 'Authentication failed. Please verify operator credentials.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div style={{ position: 'absolute', top: 20, right: 24, zIndex: 10 }}>
        <ThemeToggle />
      </div>

      <div className="login-card">
        <div className="login-header">
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
            <span style={{ fontSize: 22, fontWeight: 800, color: 'var(--color-harbor)', letterSpacing: '-0.02em' }}>
              Notify
            </span>
            <span className="badge-ops">OPS</span>
          </div>
          <h1 style={{ fontSize: 18, fontWeight: 700, color: 'var(--color-harbor)', letterSpacing: '-0.01em' }}>
            Operator Console Sign In
          </h1>
          <p style={{ fontSize: 13, color: 'var(--color-ink-2)' }}>
            System-level administrative access
          </p>
        </div>

        {error && (
          <div
            className="login-error"
            role="alert"
            style={{
              marginBottom: 16,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <ShieldAlert size={16} />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="login-form">
          <div className="form-group">
            <label htmlFor="admin-email">Operator Email</label>
            <input
              id="admin-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoFocus
              placeholder="admin@notify.dev"
              autoComplete="username"
            />
          </div>

          <div className="form-group">
            <label htmlFor="admin-password">Password</label>
            <div className="password-input-wrapper">
              <input
                id="admin-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                placeholder="••••••••••••"
                autoComplete="current-password"
              />
              <button
                type="button"
                className="password-toggle-btn"
                onClick={() => setShowPassword(prev => !prev)}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                title={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={loading}
            style={{ width: '100%', padding: '10px 16px', marginTop: 6 }}
          >
            {loading ? (
              <>
                <Loader2 size={16} className="spin-slow" />
                <span>Authenticating…</span>
              </>
            ) : (
              'Sign In to Console'
            )}
          </button>
        </form>

        <div className="login-footer">
          <Link
            to="/login"
            className="btn btn-ghost btn-sm"
            style={{
              textDecoration: 'none',
              fontSize: 12,
              gap: 6,
            }}
          >
            <ArrowLeft size={13} />
            <span>Return to Tenant Portal</span>
          </Link>
        </div>
      </div>
    </div>
  );
}
