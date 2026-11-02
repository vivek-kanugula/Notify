import React from 'react';
import type { Channel, NotificationStatus } from '../api/notifications';
import { FILTER_CHANNELS, FILTER_STATUSES } from './notificationsFilterLogic';
import { getStatusColor, getStatusIcon } from './LifecycleRail';
import { Search, X, Mail, Webhook, Bell, RotateCcw } from 'lucide-react';

export interface FilterBarProps {
  channel: Channel | '';
  status: NotificationStatus | '';
  search: string;
  onChannelChange: (channel: Channel | '') => void;
  onStatusChange: (status: NotificationStatus | '') => void;
  onSearchChange: (query: string) => void;
  onClearFilters: () => void;
  onRefresh?: () => void;
  isFiltered: boolean;
  statusCounts?: Partial<Record<NotificationStatus, number>>;
  className?: string;
}

export function getChannelIcon(channel: Channel, size = 13) {
  switch (channel) {
    case 'EMAIL':
      return <Mail size={size} aria-hidden="true" />;
    case 'WEBHOOK':
      return <Webhook size={size} aria-hidden="true" />;
    case 'IN_APP':
      return <Bell size={size} aria-hidden="true" />;
    default:
      return null;
  }
}

export const FilterBar: React.FC<FilterBarProps> = ({
  channel,
  status,
  search,
  onChannelChange,
  onStatusChange,
  onSearchChange,
  onClearFilters,
  onRefresh,
  isFiltered,
  statusCounts,
  className = '',
}) => {
  return (
    <div
      className={`filter-bar-container panel ${className}`}
      style={{
        padding: '14px 18px',
        marginBottom: 16,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
      }}
    >
      {/* Top Row: Search and Channel controls */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
          justifyContent: 'space-between',
        }}
      >
        {/* Search input with icon */}
        <div
          style={{
            position: 'relative',
            display: 'flex',
            alignItems: 'center',
            flex: 1,
            minWidth: 240,
            maxWidth: 420,
          }}
        >
          <Search
            size={14}
            style={{
              position: 'absolute',
              left: 10,
              color: 'var(--color-ink-3)',
              pointerEvents: 'none',
            }}
          />
          <input
            type="text"
            value={search}
            onChange={(e) => onSearchChange(e.target.value)}
            placeholder="Search recipient or subject…"
            style={{
              width: '100%',
              padding: '7px 28px 7px 30px',
              fontSize: 13,
              borderRadius: 'var(--radius-control)',
              border: '1px solid var(--color-rail)',
              outline: 'none',
              fontFamily: 'var(--font-sans)',
              backgroundColor: 'var(--color-paper)',
              color: 'var(--color-harbor)',
            }}
          />
          {search && (
            <button
              type="button"
              onClick={() => onSearchChange('')}
              title="Clear search"
              style={{
                position: 'absolute',
                right: 8,
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                color: 'var(--color-ink-3)',
                display: 'flex',
                alignItems: 'center',
                padding: 2,
              }}
            >
              <X size={14} />
            </button>
          )}
        </div>

        {/* Channel Segmented Control */}
        <div
          style={{
            display: 'inline-flex',
            backgroundColor: 'var(--color-subtle)',
            borderRadius: 'var(--radius-control)',
            padding: 2,
            border: '1px solid var(--color-rail)',
          }}
        >
          <button
            type="button"
            onClick={() => onChannelChange('')}
            style={{
              padding: '4px 10px',
              fontSize: 12,
              fontWeight: channel === '' ? 600 : 500,
              backgroundColor: channel === '' ? 'var(--color-paper)' : 'transparent',
              color: channel === '' ? 'var(--color-harbor)' : 'var(--color-ink-2)',
              border: 'none',
              borderRadius: 4,
              cursor: 'pointer',
              boxShadow: channel === '' ? '0 1px 2px rgba(0, 0, 0, 0.06)' : 'none',
              transition: 'all 0.15s ease-out',
            }}
          >
            All Channels
          </button>
          {FILTER_CHANNELS.map((ch) => {
            const active = channel === ch;
            return (
              <button
                key={ch}
                type="button"
                onClick={() => onChannelChange(ch)}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 5,
                  padding: '4px 10px',
                  fontSize: 12,
                  fontWeight: active ? 600 : 500,
                  backgroundColor: active ? 'var(--color-paper)' : 'transparent',
                  color: active ? 'var(--color-harbor)' : 'var(--color-ink-2)',
                  border: 'none',
                  borderRadius: 4,
                  cursor: 'pointer',
                  boxShadow: active ? '0 1px 2px rgba(0, 0, 0, 0.06)' : 'none',
                  transition: 'all 0.15s ease-out',
                }}
              >
                {getChannelIcon(ch, 12)}
                <span>{ch === 'IN_APP' ? 'In-App' : ch.charAt(0) + ch.slice(1).toLowerCase()}</span>
              </button>
            );
          })}
        </div>

        {/* Clear and Refresh Actions */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {isFiltered && (
            <button
              type="button"
              onClick={onClearFilters}
              className="btn btn-ghost btn-sm"
              style={{
                color: 'var(--color-signal)',
                backgroundColor: 'var(--color-signal-subtle)',
                border: '1px solid var(--color-signal-border)',
                gap: 4,
              }}
            >
              <RotateCcw size={12} />
              Clear filters
            </button>
          )}

          {onRefresh && (
            <button
              type="button"
              onClick={onRefresh}
              title="Refresh list"
              className="btn btn-secondary btn-sm"
            >
              Refresh
            </button>
          )}
        </div>
      </div>

      {/* Bottom Row: Status Filter Chips */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
          paddingTop: 10,
          borderTop: '1px solid var(--color-rail-subtle)',
        }}
      >
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-ink-3)', marginRight: 4 }}>
          Status:
        </span>

        {/* All Status Chip */}
        <button
          type="button"
          onClick={() => onStatusChange('')}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '3px 9px',
            fontSize: 11,
            fontWeight: status === '' ? 700 : 500,
            borderRadius: 'var(--radius-chip)',
            border: status === '' ? '1px solid var(--color-signal)' : '1px solid var(--color-rail)',
            backgroundColor: status === '' ? 'var(--color-signal)' : 'var(--color-paper)',
            color: status === '' ? '#FFFFFF' : 'var(--color-ink-2)',
            cursor: 'pointer',
            transition: 'all 0.15s ease-out',
          }}
        >
          All
        </button>

        {/* Status specific chips */}
        {FILTER_STATUSES.map((st) => {
          const active = status === st;
          const colors = getStatusColor(st);
          const count = statusCounts?.[st];

          return (
            <button
              key={st}
              type="button"
              onClick={() => onStatusChange(active ? '' : st)}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '3px 9px',
                fontSize: 11,
                fontWeight: active ? 700 : 500,
                borderRadius: 'var(--radius-chip)',
                border: active ? `1.5px solid ${colors.text}` : '1px solid var(--color-rail)',
                backgroundColor: active ? colors.bg : 'var(--color-paper)',
                color: active ? colors.text : 'var(--color-ink-2)',
                cursor: 'pointer',
                transition: 'all 0.15s ease-out',
              }}
            >
              {getStatusIcon(st, 11)}
              <span>{st}</span>
              {typeof count === 'number' && (
                <span
                  className="mono tabular-nums"
                  style={{
                    fontSize: 10,
                    opacity: 0.85,
                    marginLeft: 2,
                  }}
                >
                  ({count})
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default FilterBar;
