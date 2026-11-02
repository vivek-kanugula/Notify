-- Flyway Migration: V4__notification_trace_context.sql
-- Additive forward migration: persist W3C trace context on notifications table.
-- Preserves traceparent across asynchronous Quartz scheduling, retry releases, and background reconcilers.

ALTER TABLE notifications ADD COLUMN trace_context VARCHAR(256);
