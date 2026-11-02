import React from 'react';
import { Sun, Moon } from 'lucide-react';
import { useTheme } from '../context/ThemeContext';

export interface ThemeToggleProps {
  variant?: 'default' | 'icon' | 'signal';
  className?: string;
  style?: React.CSSProperties;
}

export const ThemeToggle: React.FC<ThemeToggleProps> = ({
  variant = 'default',
  className = '',
  style,
}) => {
  const { isDark, toggleTheme } = useTheme();

  if (variant === 'signal') {
    return (
      <button
        type="button"
        onClick={toggleTheme}
        className={`theme-toggle-btn ${className}`}
        aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
        title={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '2px 8px',
          borderRadius: 4,
          border: '1px solid rgba(255, 255, 255, 0.25)',
          backgroundColor: 'rgba(255, 255, 255, 0.12)',
          color: '#FFFFFF',
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.04em',
          cursor: 'pointer',
          transition: 'all 0.15s ease',
          ...style,
        }}
      >
        {isDark ? (
          <>
            <Sun size={12} color="#FBBF24" aria-hidden="true" />
            <span>LIGHT</span>
          </>
        ) : (
          <>
            <Moon size={12} color="#FFFFFF" aria-hidden="true" />
            <span>DARK</span>
          </>
        )}
      </button>
    );
  }

  if (variant === 'icon') {
    return (
      <button
        type="button"
        onClick={toggleTheme}
        className={`theme-toggle-btn ${className}`}
        aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
        title={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 32,
          height: 32,
          borderRadius: 'var(--radius-control)',
          border: '1px solid var(--color-rail)',
          backgroundColor: 'var(--color-paper)',
          color: 'var(--color-harbor)',
          cursor: 'pointer',
          transition: 'all 0.15s ease-out',
          ...style,
        }}
      >
        {isDark ? (
          <Sun size={15} color="#FBBF24" aria-hidden="true" />
        ) : (
          <Moon size={15} color="var(--color-ink-2)" aria-hidden="true" />
        )}
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={toggleTheme}
      className={`theme-toggle-btn ${className}`}
      aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
      title={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '5px 10px',
        borderRadius: 'var(--radius-control)',
        border: '1px solid var(--color-rail)',
        backgroundColor: 'var(--color-paper)',
        color: 'var(--color-harbor)',
        fontSize: 12,
        fontWeight: 600,
        cursor: 'pointer',
        transition: 'all 0.15s ease-out',
        ...style,
      }}
    >
      {isDark ? (
        <>
          <Sun size={13} color="#FBBF24" aria-hidden="true" />
          <span>Light</span>
        </>
      ) : (
        <>
          <Moon size={13} color="var(--color-ink-2)" aria-hidden="true" />
          <span>Dark</span>
        </>
      )}
    </button>
  );
};

export default ThemeToggle;
