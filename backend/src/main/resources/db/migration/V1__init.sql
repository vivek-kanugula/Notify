-- Notify PostgreSQL Baseline Schema
-- Flyway Migration: V1__init.sql
-- Conforms to 05_BACKEND_SCHEMA.md, CONTRACTS.md, and approved amendments , , , , 

-- 1. Tenants (Root tenant organization)
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    status_reason VARCHAR(255),
    status_changed_at TIMESTAMPTZ,
    rate_limit_per_minute INTEGER NOT NULL DEFAULT 100 CHECK (rate_limit_per_minute > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenants_tenant_id UNIQUE (id)
);

-- 2. Tenant Users (Owner credentials)
CREATE TABLE tenant_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    google_sub VARCHAR(255) UNIQUE,
    email_verified BOOLEAN NOT NULL DEFAULT false,
    role VARCHAR(32) NOT NULL DEFAULT 'TENANT_OWNER' CHECK (role IN ('TENANT_OWNER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_users_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT chk_tenant_users_auth CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL)
);

-- 3. Admin Users (Seeded operator identity)
CREATE TABLE admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'PLATFORM_ADMIN' CHECK (role IN ('PLATFORM_ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4. Refresh Tokens (Polymorphic session store without FK)
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type VARCHAR(32) NOT NULL CHECK (principal_type IN ('TENANT_USER', 'ADMIN')),
    principal_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    user_agent VARCHAR(512),
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 5. API Keys (Tenant developer credentials)
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    key_prefix VARCHAR(16) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    scopes TEXT[] NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at TIMESTAMPTZ,
    rotated_to_id UUID REFERENCES api_keys(id),
    last_used_at TIMESTAMPTZ,
    last_used_ip VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_keys_tenant_id UNIQUE (tenant_id, id)
);

-- 6. Webhook Endpoints (1-to-1 tenant configuration)
CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    url VARCHAR(2048) NOT NULL,
    is_demo BOOLEAN NOT NULL DEFAULT false,
    enabled BOOLEAN NOT NULL DEFAULT true,
    signing_secret_enc VARCHAR(512) NOT NULL,
    status_callbacks BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_endpoints_tenant_id UNIQUE (tenant_id, id)
);

-- 7. Templates (Reusable message definitions)
CREATE TABLE templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK', 'IN_APP')),
    description VARCHAR(512),
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_templates_tenant_id UNIQUE (tenant_id, id)
);

-- 8. Template Versions (Numbered, immutable versions)
CREATE TABLE template_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    template_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    subject VARCHAR(512),
    body TEXT NOT NULL,
    content_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' CHECK (content_type IN ('TEXT', 'HTML')),
    variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_versions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_template_versions_version UNIQUE (template_id, version_number),
    CONSTRAINT fk_template_versions_template FOREIGN KEY (tenant_id, template_id) REFERENCES templates(tenant_id, id) ON DELETE CASCADE
);

-- 9. Notifications (Core dispatch entities with generation and cycle fencing)
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel VARCHAR(32) NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK', 'IN_APP')),
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'SCHEDULED', 'QUEUED', 'PROCESSING', 'RETRYING', 'DELIVERED', 'FAILED', 'DLQ', 'CANCELLED')),
    recipient VARCHAR(512) NOT NULL,
    subject VARCHAR(512),
    body TEXT NOT NULL,
    template_version_id UUID,
    template_variables JSONB,
    metadata JSONB,
    idempotency_key VARCHAR(128),
    request_hash VARCHAR(64),
    scheduled_at TIMESTAMPTZ,
    queued_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    first_processing_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    deferral_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    provider VARCHAR(64),
    provider_message_id VARCHAR(64),
    cancel_reason VARCHAR(64),
    dispatch_generation INTEGER NOT NULL DEFAULT 0,
    delivery_cycle INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notifications_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_notifications_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_notifications_template_version FOREIGN KEY (tenant_id, template_version_id) REFERENCES template_versions(tenant_id, id) ON DELETE SET NULL
);

-- 10. Notification Status History (Append-only lifecycle audit)
CREATE TABLE notification_status_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    notification_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL CHECK (to_status IN ('CREATED', 'SCHEDULED', 'QUEUED', 'PROCESSING', 'RETRYING', 'DELIVERED', 'FAILED', 'DLQ', 'CANCELLED')),
    reason VARCHAR(64),
    actor VARCHAR(32) NOT NULL CHECK (actor IN ('SYSTEM', 'TENANT', 'ADMIN')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_status_history_notification FOREIGN KEY (tenant_id, notification_id) REFERENCES notifications(tenant_id, id) ON DELETE CASCADE
);

-- 11. Delivery Attempts (Cycle-aware attempt history)
CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    notification_id UUID NOT NULL,
    delivery_cycle INTEGER NOT NULL DEFAULT 1,
    attempt_number INTEGER NOT NULL,
    provider VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    http_status INTEGER,
    response_snippet VARCHAR(4096),
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_delivery_attempts_notification FOREIGN KEY (tenant_id, notification_id) REFERENCES notifications(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_delivery_attempts_cycle_attempt UNIQUE (notification_id, delivery_cycle, attempt_number)
);

-- 12. Outbox Events (Transactional outbox for Kafka)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    trace_context VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 13. Dead Letter Entries (DLQ materialization with replay reopen support)
CREATE TABLE dead_letter_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    notification_id UUID NOT NULL UNIQUE,
    channel VARCHAR(32) NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK', 'IN_APP')),
    reason VARCHAR(64) NOT NULL CHECK (reason IN ('MAX_RETRIES_EXCEEDED', 'PERMANENT_FAILURE', 'TENANT_NOT_ACTIVE', 'POISON_MESSAGE')),
    last_error_code VARCHAR(64),
    attempt_count INTEGER NOT NULL,
    payload_snapshot JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REPLAYED', 'DISCARDED')),
    replay_count INTEGER NOT NULL DEFAULT 0,
    resolution_note VARCHAR(1024),
    resolved_by VARCHAR(255),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_dead_letter_entries_notification FOREIGN KEY (tenant_id, notification_id) REFERENCES notifications(tenant_id, id) ON DELETE CASCADE
);

-- 14. In-App Messages (SSE persistent storage)
CREATE TABLE inapp_messages (
    seq BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    notification_id UUID NOT NULL UNIQUE,
    recipient_ref VARCHAR(255) NOT NULL,
    subject VARCHAR(512),
    body TEXT NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_inapp_messages_notification FOREIGN KEY (tenant_id, notification_id) REFERENCES notifications(tenant_id, id) ON DELETE CASCADE
);

-- 15. Demo Webhook Receipts (Shared sandbox logging)
CREATE TABLE demo_webhook_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    notification_id UUID,
    attempt_id UUID,
    signature_valid BOOLEAN NOT NULL DEFAULT false,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 16. Admin Audit Log (Immutable operator action records)
CREATE TABLE admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    admin_id UUID,
    admin_email VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL CHECK (action IN ('TENANT_SUSPENDED', 'TENANT_REVOKED', 'TENANT_REACTIVATED', 'TENANT_DELETED', 'DLQ_REPLAYED', 'DLQ_DISCARDED', 'LOGIN')),
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    tenant_id UUID,
    tenant_slug VARCHAR(64),
    reason VARCHAR(512),
    details JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ==============================================================================
-- Indexes
-- ==============================================================================

-- Partial work-queue indexes
CREATE INDEX idx_notifications_scheduled ON notifications (scheduled_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_notifications_retrying ON notifications (next_retry_at) WHERE status = 'RETRYING';
CREATE INDEX idx_notifications_processing ON notifications (processing_started_at) WHERE status = 'PROCESSING';
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_dlq_open ON dead_letter_entries (created_at) WHERE status = 'OPEN';
CREATE UNIQUE INDEX uq_template_one_published ON template_versions (template_id) WHERE status = 'PUBLISHED';
CREATE UNIQUE INDEX uq_templates_active_name ON templates (tenant_id, lower(name)) WHERE deleted_at IS NULL;

-- Composite B-Tree query indexes
CREATE INDEX idx_notifications_tenant_created ON notifications (tenant_id, created_at DESC);
CREATE INDEX idx_notifications_tenant_status ON notifications (tenant_id, status, created_at DESC);
CREATE INDEX idx_status_history_tenant_notif ON notification_status_history (tenant_id, notification_id, occurred_at ASC);
CREATE INDEX idx_attempts_tenant_notif ON delivery_attempts (tenant_id, notification_id, delivery_cycle, attempt_number);
CREATE INDEX idx_inapp_tenant_recipient_seq ON inapp_messages (tenant_id, recipient_ref, seq DESC);
CREATE INDEX idx_api_keys_hash ON api_keys (key_hash);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_tenants_slug ON tenants (slug);
