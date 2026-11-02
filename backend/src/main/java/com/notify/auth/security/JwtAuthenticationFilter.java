package com.notify.auth.security;

import com.notify.auth.service.JwtDenylistService;
import com.notify.auth.service.JwtService;
import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import io.jsonwebtoken.Claims;
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
import java.util.*;

/**
 * JWT authentication filter. Extracts Bearer token from Authorization header,
 * validates it, checks denylist, and sets SecurityContext + TenantContext.
 * Always clears TenantContext after the request per AGENTS.md.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final JwtDenylistService jwtDenylistService;

    public JwtAuthenticationFilter(JwtService jwtService, JwtDenylistService jwtDenylistService) {
        this.jwtService = jwtService;
        this.jwtDenylistService = jwtDenylistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractBearerToken(request);
            if (token != null) {
                Claims claims = jwtService.parseToken(token);
                if (claims != null) {
                    String jti = claims.getId();

                    // Check JWT denylist
                    if (jti != null && jwtDenylistService.isDenied(jti)) {
                        log.debug("JWT with jti {} is denied", jti);
                        filterChain.doFilter(request, response);
                        return;
                    }

                    // Build authorities from roles claim
                    @SuppressWarnings("unchecked")
                    List<String> roles = claims.get("roles", List.class);
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    if (roles != null) {
                        for (String role : roles) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                        }
                    }

                    // Get audience
                    Set<String> audiences = claims.getAudience();
                    String audience = audiences != null && !audiences.isEmpty()
                            ? audiences.iterator().next() : "";

                    // Build authentication principal info
                    Map<String, Object> principalInfo = new HashMap<>();
                    principalInfo.put("sub", claims.getSubject());
                    principalInfo.put("aud", audience);
                    principalInfo.put("jti", jti);
                    if (claims.get("tid") != null) {
                        principalInfo.put("tid", claims.get("tid", String.class));
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principalInfo, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // Set TenantContext for tenant JWTs
                    if ("notify-tenant".equals(audience) && claims.get("tid") != null) {
                        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
                        UUID userId = UUID.fromString(claims.getSubject());
                        TenantContext.set(TenantPrincipal.forUser(tenantId, userId, Set.of()));
                        // / correlate structured logs with the tenant (traceId/spanId come from tracing).
                        org.slf4j.MDC.put("tenantId", tenantId.toString());
                    }
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // Always clear TenantContext and the tenant MDC key after the request
            TenantContext.clear();
            org.slf4j.MDC.remove("tenantId");
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
