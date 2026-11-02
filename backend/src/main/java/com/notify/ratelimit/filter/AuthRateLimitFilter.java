package com.notify.ratelimit.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.error.ErrorCode;
import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter applying IP-based rate limiting to authentication routes (/auth/login, /auth/signup)
 * per CONTRACTS.md §Redis.
 * Only trusts X-Forwarded-For if the request arrived from an explicitly trusted proxy.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final int DEFAULT_AUTH_IP_LIMIT = 30; // 30 req/min per IP
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final Set<String> trustedProxies;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthRateLimitFilter(
            RateLimitService rateLimitService,
            @Value("${notify.security.trusted-proxies:}") String trustedProxiesConfig
    ) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.trustedProxies = parseTrustedProxies(trustedProxiesConfig);
    }

    public AuthRateLimitFilter(RateLimitService rateLimitService, Set<String> trustedProxies) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.trustedProxies = trustedProxies != null ? trustedProxies : Collections.emptySet();
    }

    private static Set<String> parseTrustedProxies(String config) {
        if (config == null || config.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (uri != null && (uri.equals("/auth/login") || uri.equals("/auth/signup") || uri.equals("/auth/admin/login"))) {
            String clientIp = resolveClientIp(request);
            try {
                rateLimitService.checkIpLimit(clientIp, uri, DEFAULT_AUTH_IP_LIMIT);
            } catch (RateLimitException ex) {
                response.setStatus(429);
                response.setContentType("application/problem+json");
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));

                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        ex.getMessage()
                );
                problem.setTitle(ErrorCode.RATE_LIMITED.name());
                problem.setType(URI.create("https://notify.dev/errors/rate_limited"));
                problem.setProperty("errorCode", ErrorCode.RATE_LIMITED.name());
                problem.setProperty("timestamp", Instant.now());
                problem.setProperty("retryAfter", ex.getRetryAfterSeconds());

                response.getWriter().write(objectMapper.writeValueAsString(problem));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    public String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }

        // Only trust proxy headers if request came from an explicitly trusted proxy
        if (isTrustedProxy(remoteAddr)) {
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = parts[i].trim();
                    if (!isTrustedProxy(candidate)) {
                        return candidate;
                    }
                }
                return parts[0].trim();
            }
        }

        // Direct connection or untrusted proxy: remoteAddr is authoritative
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return trustedProxies != null && trustedProxies.contains(remoteAddr);
    }
}
