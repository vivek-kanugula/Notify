package com.notify.admin.security;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

/**
 * Shared admin security utilities for audience verification and principal extraction.
 *
 * <p>Every admin endpoint that crosses tenant boundaries must verify
 * {@code aud=notify-admin}. This utility centralises the check so it is
 * never accidentally omitted.
 */
public final class AdminSecurityUtils {

    private AdminSecurityUtils() {
    }

    /**
     * Verify that the authentication carries a {@code notify-admin} audience
     * and return the principal claims map.
     *
     * @throws NotifyException with {@code UNAUTHENTICATED} or {@code FORBIDDEN}
     */
    public static Map<?, ?> requireAdminPrincipal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof Map<?, ?> principal)) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Admin authentication required");
        }
        if (!"notify-admin".equals(principal.get("aud"))) {
            throw new NotifyException(ErrorCode.FORBIDDEN, "Cross-audience access forbidden");
        }
        return principal;
    }

    /**
     * Extract the admin user ID ({@code sub} claim) from the authenticated principal.
     */
    public static UUID adminId(Map<?, ?> principal) {
        return UUID.fromString((String) principal.get("sub"));
    }

    /**
     * Determine the client IP address. Uses {@code request.getRemoteAddr()} to prevent
     * untrusted {@code X-Forwarded-For} header spoofing from poisoning the audit trail.
     * When a trusted reverse proxy is configured, Spring's ForwardedHeaderFilter or
     * {@code server.forward-headers-strategy} safely unwraps the verified remote address.
     */
    public static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
