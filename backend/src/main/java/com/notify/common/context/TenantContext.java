package com.notify.common.context;

import java.util.Optional;

/**
 * Thread-local holder for verified {@link TenantPrincipal}.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantPrincipal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantPrincipal principal) {
        CURRENT_PRINCIPAL.set(principal);
    }

    public static Optional<TenantPrincipal> get() {
        return Optional.ofNullable(CURRENT_PRINCIPAL.get());
    }

    public static TenantPrincipal require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated tenant principal in TenantContext"));
    }

    public static void clear() {
        CURRENT_PRINCIPAL.remove();
    }
}
