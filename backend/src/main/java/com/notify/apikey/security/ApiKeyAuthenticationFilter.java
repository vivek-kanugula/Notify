package com.notify.apikey.security;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.apikey.cache.CachedApiKey;
import com.notify.apikey.service.ApiKeyService;
import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.model.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.*;

/**
 * API key authentication filter.
 *
 * Extracts API key from {@code X-API-Key} header or {@code Authorization: Bearer ntf_...}.
 * Resolves metadata via {@link ApiKeyStatusCacheService} (Redis 60s TTL / DB fallback).
 * Enforces tenant lifecycle status matrix ():
 * - ACTIVE: full access
 * - SUSPENDED: API keys blocked with 403 TENANT_SUSPENDED
 * - REVOKED: API keys blocked with 403 TENANT_REVOKED
 *
 * Scopes are assigned as {@code SCOPE_<scope>} granted authorities (no role per CONTRACTS.md).
 * Clears {@link TenantContext} in {@code finally} block.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyStatusCacheService cacheService;
    private final ApiKeyService apiKeyService;
    private final Clock clock;

    public ApiKeyAuthenticationFilter(ApiKeyStatusCacheService cacheService,
                                      ApiKeyService apiKeyService,
                                      Clock clock) {
        this.cacheService = cacheService;
        this.apiKeyService = apiKeyService;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawKey = extractApiKey(request);
        if (rawKey == null) {
            // Not an API key request — pass through to subsequent filters (e.g. JwtAuthenticationFilter)
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String keyHash = ApiKeyService.sha256Hex(rawKey.trim());
            Optional<CachedApiKey> keyOpt = cacheService.getApiKey(keyHash);

            if (keyOpt.isEmpty()) {
                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_API_KEY", "Invalid API key");
                return;
            }

            CachedApiKey key = keyOpt.get();

            // Check if key is revoked
            if ("REVOKED".equals(key.status())) {
                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_API_KEY", "API key has been revoked");
                return;
            }

            // Check if key is expired
            if (key.expiresAt() != null && key.expiresAt().isBefore(clock.instant())) {
                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "API_KEY_EXPIRED", "API key has expired");
                return;
            }

            // Enforce tenant status matrix ()
            Optional<TenantStatus> tenantStatusOpt = cacheService.getTenantStatus(key.tenantId());
            if (tenantStatusOpt.isEmpty()) {
                writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_API_KEY", "Tenant not found");
                return;
            }

            TenantStatus tenantStatus = tenantStatusOpt.get();
            if (tenantStatus == TenantStatus.SUSPENDED) {
                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "TENANT_SUSPENDED", "Tenant is suspended");
                return;
            }
            if (tenantStatus == TenantStatus.REVOKED) {
                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "TENANT_REVOKED", "Tenant is revoked");
                return;
            }

            // Populate TenantContext
            Set<String> scopes = new HashSet<>(key.scopes());
            TenantPrincipal principal = TenantPrincipal.forApiKey(key.tenantId(), key.id(), scopes);
            TenantContext.set(principal);

            // Populate Spring Security Context (authorities = SCOPE_<scope>, no role per CONTRACTS.md)
            List<SimpleGrantedAuthority> authorities = key.scopes().stream()
                    .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                    .toList();

            Map<String, Object> principalInfo = new HashMap<>();
            principalInfo.put("sub", key.id().toString());
            principalInfo.put("tid", key.tenantId().toString());
            principalInfo.put("authType", "API_KEY");
            principalInfo.put("scopes", key.scopes());

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principalInfo, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Throttled last-used tracking
            try {
                apiKeyService.recordLastUsed(key.id(), getClientIp(request));
            } catch (Exception e) {
                log.warn("Failed to record last used for API key id={}: {}", key.id(), e.getMessage());
            }

            filterChain.doFilter(request, response);
        } finally {
            // Always guarantee TenantContext is cleaned up
            TenantContext.clear();
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        String xApiKey = request.getHeader("X-API-Key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ntf_")) {
            return authHeader.substring(7);
        }

        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = null;
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            ip = xRealIp.trim();
        } else {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                String[] parts = xForwardedFor.split(",");
                ip = parts[parts.length - 1].trim();
            } else {
                ip = request.getRemoteAddr();
            }
        }
        if (ip != null && ip.length() > 45) {
            ip = ip.substring(0, 45);
        }
        return ip;
    }

    private void writeProblem(HttpServletResponse response, int status, String errorCode, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        String json = """
                {"type":"https://notify.dev/errors/%s","title":"%s","status":%d,"detail":"%s","errorCode":"%s"}
                """.formatted(errorCode.toLowerCase(), errorCode, status, detail, errorCode);
        response.getWriter().write(json);
    }
}
