import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAdminAuth } from '../../auth/AdminAuthContext';
import ThemeToggle from '../../components/ThemeToggle';
import {
  Users,
  Bell,
  Archive,
  Activity,
  FileText,
  BarChart2,
  LogOut,
  Shield,
} from 'lucide-react';

export default function AdminDashboardShell() {
  const { admin, logout } = useAdminAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/admin/login', { replace: true });
  };

  const navItems = [
    { to: '/admin', label: 'Overview', icon: BarChart2, end: true },
    { to: '/admin/tenants', label: 'Tenants', icon: Users },
    { to: '/admin/notifications', label: 'Notifications', icon: Bell },
    { to: '/admin/dlq', label: 'Dead-letter queue', icon: Archive },
    { to: '/admin/system', label: 'System status', icon: Activity },
    { to: '/admin/audit', label: 'Audit log', icon: FileText },
  ];

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', backgroundColor: 'var(--color-chalk)' }}>
      {/* 1. Slim Signal Banner */}
      <div className="admin-signal-bar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Shield size={13} strokeWidth={2.5} />
          <span>ADMIN CONSOLE — OPERATOR ACCESS ONLY</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, opacity: 0.95 }}>
          <span style={{ fontSize: 11, fontWeight: 600 }}>ENV: PRODUCTION / ISOLATED</span>
          <span className="badge-mono" style={{ fontSize: 10, padding: '1px 5px', color: '#FFFFFF', borderColor: 'rgba(255,255,255,0.2)' }}>
            UTC
          </span>
          <ThemeToggle variant="signal" />
        </div>
      </div>

      {/* 2. Shell Body with Sidebar and Main Content */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Left Sidebar */}
        <aside
          className="admin-sidebar"
          style={{
            width: 240,
            backgroundColor: 'var(--color-sidebar-bg)',
            color: '#FFFFFF',
            display: 'flex',
            flexDirection: 'column',
            flexShrink: 0,
            borderRight: '1px solid var(--color-sidebar-border)',
          }}
        >
          {/* Brand */}
          <div
            style={{
              height: 54,
              display: 'flex',
              alignItems: 'center',
              padding: '0 20px',
              borderBottom: '1px solid var(--color-sidebar-border)',
            }}
          >
            <div
              style={{
                width: 28,
                height: 28,
                borderRadius: 'var(--radius-control)',
                backgroundColor: 'var(--color-signal)',
                display: 'grid',
                placeItems: 'center',
                fontWeight: 800,
                color: '#FFFFFF',
                fontSize: 14,
                marginRight: 10,
              }}
            >
              N
            </div>
            <span style={{ fontSize: 16, fontWeight: 800, letterSpacing: '-0.02em', color: '#FFFFFF' }}>
              Notify
            </span>
            <span className="badge-ops">OPS</span>
          </div>

          {/* Nav Items */}
          <nav style={{ flex: 1, padding: '16px 10px', display: 'flex', flexDirection: 'column', gap: 2 }}>
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                >
                  <Icon size={16} />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>

          {/* Footer Operator Info */}
          <div
            style={{
              padding: '16px 16px',
              borderTop: '1px solid var(--color-sidebar-border)',
              backgroundColor: 'rgba(0,0,0,0.2)',
            }}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginBottom: 12 }}>
              <span
                style={{
                  fontSize: 12,
                  fontWeight: 600,
                  color: '#FFFFFF',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
                title={admin?.email}
              >
                {admin?.email || 'operator@notify.dev'}
              </span>
              <span
                className="badge-mono"
                style={{
                  fontSize: 10,
                  color: 'var(--color-sidebar-text)',
                  borderColor: 'transparent',
                  padding: 0,
                  background: 'none',
                  textTransform: 'uppercase',
                }}
              >
                ROLE_PLATFORM_ADMIN
              </span>
            </div>

            <button
              type="button"
              onClick={handleLogout}
              className="logout-button"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 6,
              }}
            >
              <LogOut size={13} />
              <span>Sign Out</span>
            </button>
          </div>
        </aside>

        {/* Main Content Area */}
        <main
          style={{
            flex: 1,
            overflowY: 'auto',
            padding: '24px 28px',
            backgroundColor: 'var(--color-chalk)',
          }}
        >
          <div style={{ maxWidth: 1240, margin: '0 auto' }}>
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
