import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ThemeProvider } from './context/ThemeContext';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { AdminAuthProvider, useAdminAuth } from './auth/AdminAuthContext';
import LoginPage from './pages/LoginPage';
import DashboardShell from './pages/DashboardShell';
import LandingPage from './pages/LandingPage';

// Admin Pages
import AdminLoginPage from './pages/admin/AdminLoginPage';
import AdminDashboardShell from './pages/admin/AdminDashboardShell';
import AdminOverviewPage from './pages/admin/AdminOverviewPage';
import AdminTenantsPage from './pages/admin/AdminTenantsPage';
import AdminTenantDetailPage from './pages/admin/AdminTenantDetailPage';
import AdminNotificationsPage from './pages/admin/AdminNotificationsPage';
import AdminNotificationDetailPage from './pages/admin/AdminNotificationDetailPage';
import AdminDlqPage from './pages/admin/AdminDlqPage';
import AdminDlqDetailPage from './pages/admin/AdminDlqDetailPage';
import AdminSystemPage from './pages/admin/AdminSystemPage';
import AdminAuditPage from './pages/admin/AdminAuditPage';

import type { ReactNode } from 'react';

function ProtectedRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="loading-screen">
        <div className="loading-spinner" />
        <span style={{ color: 'var(--color-ink-2)', marginTop: 12 }}>Loading…</span>
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}

function PublicOnlyRoute({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="loading-screen">
        <div className="loading-spinner" />
      </div>
    );
  }

  if (user) {
    return <Navigate to="/app" replace />;
  }

  return <>{children}</>;
}

function AdminProtectedRoute({ children }: { children: ReactNode }) {
  const { admin, loading } = useAdminAuth();

  if (loading) {
    return (
      <div className="loading-screen">
        <div className="loading-spinner" />
        <span style={{ color: 'var(--color-ink-2)', marginTop: 12 }}>Authenticating operator…</span>
      </div>
    );
  }

  if (!admin) {
    return <Navigate to="/admin/login" replace />;
  }

  return <>{children}</>;
}

function AdminPublicOnlyRoute({ children }: { children: ReactNode }) {
  const { admin, loading } = useAdminAuth();

  if (loading) {
    return (
      <div className="loading-screen">
        <div className="loading-spinner" />
      </div>
    );
  }

  if (admin) {
    return <Navigate to="/admin/tenants" replace />;
  }

  return <>{children}</>;
}

function AppRoutes() {
  return (
    <Routes>
      {/* Public Landing */}
      <Route path="/" element={<LandingPage />} />
      <Route path="/demo" element={<Navigate to="/" replace />} />
      <Route path="/signup" element={
        <PublicOnlyRoute>
          <LoginPage initialSignup={true} />
        </PublicOnlyRoute>
      } />

      {/* Tenant Auth & App */}
      <Route path="/login" element={
        <PublicOnlyRoute>
          <LoginPage />
        </PublicOnlyRoute>
      } />
      <Route path="/app/*" element={
        <ProtectedRoute>
          <DashboardShell />
        </ProtectedRoute>
      } />

      {/* Operator Admin Console */}
      <Route path="/admin/login" element={
        <AdminPublicOnlyRoute>
          <AdminLoginPage />
        </AdminPublicOnlyRoute>
      } />
      <Route path="/admin" element={
        <AdminProtectedRoute>
          <AdminDashboardShell />
        </AdminProtectedRoute>
      }>
        <Route index element={<AdminOverviewPage />} />
        <Route path="tenants" element={<AdminTenantsPage />} />
        <Route path="tenants/:id" element={<AdminTenantDetailPage />} />
        <Route path="notifications" element={<AdminNotificationsPage />} />
        <Route path="notifications/:id" element={<AdminNotificationDetailPage />} />
        <Route path="dlq" element={<AdminDlqPage />} />
        <Route path="dlq/:id" element={<AdminDlqDetailPage />} />
        <Route path="system" element={<AdminSystemPage />} />
        <Route path="audit" element={<AdminAuditPage />} />
      </Route>

      {/* Default Fallback */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <BrowserRouter>
        <AuthProvider>
          <AdminAuthProvider>
            <AppRoutes />
          </AdminAuthProvider>
        </AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}
