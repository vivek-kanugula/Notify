package com.notify.tenant.event;

import com.notify.common.model.TenantStatus;

import java.util.UUID;

/**
 * Event published when a tenant's lifecycle status changes (e.g. suspended, revoked, reactivated).
 * Listeners use this to evict caches and handle lifecycle side-effects.
 */
public record TenantStatusChangedEvent(
        UUID tenantId,
        TenantStatus previousStatus,
        TenantStatus newStatus
) {}
