package com.notify.tenant.service;

import com.notify.common.model.TenantStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant status lookup for other features (delivery worker tenant gate). Features call each other through
 * service interfaces, never through repositories.
 */
public interface TenantService {

    /**
     * Authoritative tenant status read from PostgreSQL (no cache: the worker gate must observe suspension
     * promptly; / ). Bounded to 2 s. Empty when the tenant does not exist.
     */
    Optional<TenantStatus> getTenantStatus(UUID tenantId);

    /**
     * Tenant status read under a row lock, inside the caller transaction (/ , ).
     *
     * <p>Used by the admin DLQ replay, which must decide eligibility <b>inside</b> its transaction rather than in a
     * pre-check a concurrent suspension could invalidate. Locking the {@code tenants} row first also fixes the lock
     * order - {@code tenants}, {@code notifications}, {@code dead_letter_entries}, then append-only writes - so a
     * replay and a tenant status change serialize instead of deadlocking.
     *
     * <p>MANDATORY on purpose: a lock outside a transaction would be released immediately and prove nothing.
     */
    default Optional<TenantStatus> getTenantStatusForUpdate(UUID tenantId) {
        // Default: the ordinary read. A test double or any implementation that does not own a transaction gets
        // exactly the previous behaviour, so this stays a single-abstract-method interface and existing lambdas
        // keep compiling. DefaultTenantService overrides it with the locking read the replay transaction needs.
        return getTenantStatus(tenantId);
    }
}
