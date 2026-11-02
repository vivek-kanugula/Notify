package com.notify.auth.security;

import com.notify.apikey.security.ApiKeyAuthenticationFilter;
import com.notify.config.NotifyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * Security configuration with stateless JWT, CORS locked to frontend origin,
 * and security headers per CONTRACTS.md.
 *
 * Filter chain order:
 * 1. Admin chain: /api/v1/admin/** requires ROLE_PLATFORM_ADMIN
 * 2. Auth chain: /auth/** public (login, signup, refresh)
 * 3. Default chain: everything else
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final com.notify.ratelimit.filter.AuthRateLimitFilter authRateLimitFilter;
    private final NotifyProperties notifyProperties;

    public SecurityConfig(ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          com.notify.ratelimit.filter.AuthRateLimitFilter authRateLimitFilter,
                          NotifyProperties notifyProperties) {
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.notifyProperties = notifyProperties;
    }

    private void configureCommon(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authEx) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/problem+json");
                    response.getWriter().write(
                        "{\"type\":\"https://notify.dev/errors/unauthenticated\",\"title\":\"UNAUTHENTICATED\",\"status\":401,\"detail\":\""
                        + (authEx.getMessage() != null ? authEx.getMessage().replace("\"", "\\\"") : "Unauthenticated")
                        + "\",\"errorCode\":\"UNAUTHENTICATED\"}"
                    );
                })
                .accessDeniedHandler((request, response, accessEx) -> {
                    boolean isApiKey = com.notify.common.context.TenantContext.get()
                            .map(p -> "API_KEY".equals(p.authType()))
                            .orElse(false)
                            && !request.getServletPath().startsWith("/api/v1/keys");
                    String errorCode = isApiKey ? "INSUFFICIENT_SCOPE" : "FORBIDDEN";
                    String detail = isApiKey ? "API key lacks required scope for this operation" : "Access denied";

                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/problem+json");
                    response.getWriter().write(
                        "{\"type\":\"https://notify.dev/errors/" + errorCode.toLowerCase() + "\",\"title\":\"" + errorCode + "\",\"status\":403,\"detail\":\"" + detail + "\",\"errorCode\":\"" + errorCode + "\"}"
                    );
                })
            )
            .headers(headers -> headers
                    .contentTypeOptions(opt -> {})
                    .frameOptions(frame -> frame.deny())
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000))
            );
    }

    /**
     * Admin security chain: /api/v1/admin/** requires ROLE_PLATFORM_ADMIN.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        configureCommon(http);
        http
            .securityMatcher("/api/v1/admin/**")
            .authorizeHttpRequests(auth -> auth
                    .anyRequest().access((authentication, context) -> {
                        org.springframework.security.core.Authentication a = authentication.get();
                        if (a == null || !a.isAuthenticated()) {
                            return new org.springframework.security.authorization.AuthorizationDecision(false);
                        }
                        boolean hasRole = a.getAuthorities().stream()
                                .anyMatch(authItem -> "ROLE_PLATFORM_ADMIN".equals(authItem.getAuthority()));
                        if (!hasRole) {
                            return new org.springframework.security.authorization.AuthorizationDecision(false);
                        }
                        if (a.getPrincipal() instanceof java.util.Map<?, ?> principal) {
                            return new org.springframework.security.authorization.AuthorizationDecision("notify-admin".equals(principal.get("aud")));
                        }
                        return new org.springframework.security.authorization.AuthorizationDecision(false);
                    })
            );

        return http.build();
    }

    /**
     * Auth chain: /auth/** has public login/signup/refresh/logout and authenticated /me.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain authSecurityFilterChain(HttpSecurity http) throws Exception {
        configureCommon(http);
        http
            .securityMatcher("/auth/**")
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/auth/signup", "/auth/login", "/auth/admin/login",
                            "/auth/refresh", "/auth/admin/refresh",
                            "/auth/logout", "/auth/admin/logout").permitAll()
                    .anyRequest().authenticated()
            )
            .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Default chain: actuator, health, tenant API endpoints.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        configureCommon(http);
        http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/actuator/info").permitAll()
                    // / (M5): the Prometheus scrape endpoint is admin-only. Liveness/readiness probes
                    // above stay public; everything else under /actuator requires the platform admin role.
                    .requestMatchers("/actuator/prometheus").hasRole("PLATFORM_ADMIN")
                    .requestMatchers("/actuator/**").hasRole("PLATFORM_ADMIN")
                    .requestMatchers("/api/v1/keys/**").hasRole("TENANT_OWNER")
                    // / webhook configuration creates and rotates a signing secret, so it follows the
                    // API-key rule — tenant owner JWT only, API keys rejected.
                    .requestMatchers("/api/v1/webhook-endpoint/**", "/api/v1/webhook-endpoint").hasRole("TENANT_OWNER")
                    // / the demo webhook receiver carries no credential; it authenticates each request by
                    // verifying the signature against the sending tenant's secret, and stores nothing otherwise.
                    .requestMatchers("/demo/**").permitAll()
                    // / (): the SSE stream cannot be authenticated by this chain at all — an
                    // EventSource sets no request headers, so it can send neither a JWT nor an API key. The route is
                    // therefore matched here and authorized inside the controller by atomically consuming a
                    // single-use token (fail-closed): no token, no stream, and the tenant and recipient come from
                    // the consumed token rather than from the URL.
                    .requestMatchers("/sse/inapp").permitAll()
                    // / / / (): the public demo inbox and demo stream carry no credential and are
                    // gated by notify.demo.public + notify.demo.tenant-slugs; the controllers return 404 when disabled.
                    .requestMatchers("/api/v1/demo/**").permitAll()
                    .requestMatchers("/api/v1/**").access(new org.springframework.security.web.access.expression.WebExpressionAuthorizationManager("hasRole('TENANT_OWNER') or (isAuthenticated() and !hasRole('PLATFORM_ADMIN'))"))
                    .anyRequest().authenticated()
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(notifyProperties.frontendUrl()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-API-Key", "Idempotency-Key", "X-Confirm-Tenant-Slug"));
        configuration.setExposedHeaders(List.of("Idempotent-Replay", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
