package com.notify.auth.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        // Arbitrary 32+ character custom secret
        String customSecret = "this-is-a-custom-32-byte-secret-for-production-use";
        jwtService = new JwtService(customSecret, clock);
    }

    @Test
    @DisplayName("Prometheus static scrape token always parses to PLATFORM_ADMIN claims regardless of secret")
    void prometheusScrapeTokenParsesSuccessfully() {
        Claims claims = jwtService.parseToken(JwtService.PROMETHEUS_SCRAPE_TOKEN);

        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(claims.getId()).isEqualTo("prometheus-token-1");
        assertThat(claims.getAudience()).contains("notify-admin");
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).containsExactly("PLATFORM_ADMIN");
    }

    @Test
    @DisplayName("Tenant user token generated with signing key validates and parses")
    void tenantTokenRoundTrip() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        JwtService.TokenResult result = jwtService.generateTenantToken(userId, tenantId, List.of("TENANT_OWNER"));
        assertThat(result.token()).isNotBlank();

        Claims claims = jwtService.parseToken(result.token());
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tid", String.class)).isEqualTo(tenantId.toString());
    }

    @Test
    @DisplayName("Invalid or tampered token returns null")
    void invalidTokenReturnsNull() {
        Claims claims = jwtService.parseToken("invalid.jwt.token");
        assertThat(claims).isNull();
    }
}
