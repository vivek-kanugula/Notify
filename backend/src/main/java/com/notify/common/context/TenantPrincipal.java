package com.notify.common.context;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Verified tenant security principal set by authentication filters.
 * Tenant identity comes exclusively from verified credentials, never from request path/body.
 */
public record TenantPrincipal(
    UUID tenantId,
    UUID userId,
    UUID apiKeyId,
    String authType,
    Set<String> scopes
) {
    public TenantPrincipal {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        scopes = scopes != null ? Collections.unmodifiableSet(scopes) : Collections.emptySet();
    }

    public static TenantPrincipal forUser(UUID tenantId, UUID userId, Set<String> scopes) {
        return new TenantPrincipal(tenantId, userId, null, "JWT", scopes);
    }

    public static TenantPrincipal forApiKey(UUID tenantId, UUID apiKeyId, Set<String> scopes) {
        return new TenantPrincipal(tenantId, null, apiKeyId, "API_KEY", scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
