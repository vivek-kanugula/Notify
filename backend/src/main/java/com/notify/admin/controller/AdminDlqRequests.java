package com.notify.admin.controller;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;

/**
 * Request validation shared by the two admin DLQ controllers (/ , ).
 *
 * <p>Every rejection is {@code 422 VALIDATION_FAILED}, reusing the frozen error set - an unknown enum value, a
 * malformed timestamp or cursor, an inverted range, or a limit above the configured ceiling. Nothing is silently
 * coerced or truncated: a client that asks for something out of bounds is told so.
 */
final class AdminDlqRequests {

    private AdminDlqRequests() {
    }

    static <E extends Enum<E>> E enumValue(String raw, Class<E> type, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " has an unknown value");
        }
    }

    static Instant instant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " must be an ISO-8601 instant");
        }
    }

    static void requireRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
        }
    }

    static void requireLimit(int limit, int maxLimit) {
        if (limit < 0 || limit > maxLimit) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + maxLimit);
        }
    }

    /**
     * The authenticated admin principal, or a refusal.
     *
     * <p><b>Every</b> admin DLQ handler calls this, reads included. The chain already requires
     * {@code ROLE_PLATFORM_ADMIN}, but the role claim and the audience are separate facts: a token minted for the
     * tenant audience that carries the admin role satisfies the chain while belonging to a different trust
     * boundary, and these endpoints read across every tenant. Checking the audience only on the write handlers
     * left the cross-tenant reads open to exactly that token ().
     */
    static java.util.Map<?, ?> requireAdminPrincipal(
            org.springframework.security.core.Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof java.util.Map<?, ?> principal)) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Admin authentication required");
        }
        if (!"notify-admin".equals(principal.get("aud"))) {
            throw new NotifyException(ErrorCode.FORBIDDEN, "Cross-audience access forbidden");
        }
        return principal;
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
