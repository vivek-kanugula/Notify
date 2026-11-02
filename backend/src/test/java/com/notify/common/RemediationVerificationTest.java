package com.notify.common;

import com.notify.apikey.dto.RotateApiKeyRequest;
import com.notify.auth.service.RefreshTokenService;
import com.notify.auth.entity.RefreshTokenEntity;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.GlobalExceptionHandler;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Channel;
import com.notify.common.model.ContentType;
import com.notify.common.model.PrincipalType;
import com.notify.idempotency.util.CanonicalHashCalculator;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.filter.AuthRateLimitFilter;
import com.notify.ratelimit.service.RateLimitService;
import com.notify.template.engine.TemplateRenderingEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemediationVerificationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("/ TemplateRenderingEngine: supports both nested maps and flat dotted keys")
    void templateRenderingSupportsFlatAndNestedKeys() {
        TemplateRenderingEngine engine = new TemplateRenderingEngine();

        // 1. Nested map
        String renderedNested = engine.render(
                "Hello {{user.name}}",
                Map.of("user", Map.of("name", "Alice")),
                ContentType.TEXT
        );
        assertThat(renderedNested).isEqualTo("Hello Alice");

        // 2. Flat dotted key
        String renderedFlat = engine.render(
                "Hello {{user.name}}",
                Map.of("user.name", "Bob"),
                ContentType.TEXT
        );
        assertThat(renderedFlat).isEqualTo("Hello Bob");
    }

    @Test
    @DisplayName("/ CanonicalHashCalculator: deterministic hash regardless of Set iteration order")
    void canonicalHashDeterministicWithSets() {
        Set<String> set1 = new LinkedHashSet<>(List.of("alpha", "beta", "gamma"));
        Set<String> set2 = new LinkedHashSet<>(List.of("gamma", "alpha", "beta"));

        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL, "user@example.com", "Subject", "Body",
                null, null, null, Map.of("tags", set1), "idem-1", null
        );

        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL, "user@example.com", "Subject", "Body",
                null, null, null, Map.of("tags", set2), "idem-1", null
        );

        String hash1 = CanonicalHashCalculator.computeHash(req1);
        String hash2 = CanonicalHashCalculator.computeHash(req2);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("/ CreateNotificationRequest: recipient max length is strictly 255 chars")
    void recipientMaxLengthEnforcedAt255() {
        String validRecipient = "a".repeat(255);
        String invalidRecipient = "a".repeat(256);

        CreateNotificationRequest validReq = new CreateNotificationRequest(
                Channel.WEBHOOK, validRecipient, null, "body",
                null, null, null, null, null, null
        );
        CreateNotificationRequest invalidReq = new CreateNotificationRequest(
                Channel.WEBHOOK, invalidRecipient, null, "body",
                null, null, null, null, null, null
        );

        assertThat(validator.validate(validReq)).isEmpty();
        assertThat(validator.validate(invalidReq))
                .anyMatch(v -> v.getPropertyPath().toString().equals("recipient"));
    }

    @Test
    @DisplayName("/ RotateApiKeyRequest: gracePeriodHours bounded to max 168 (7 days)")
    void rotateApiKeyRequestGracePeriodBounded() {
        RotateApiKeyRequest validReq = new RotateApiKeyRequest("key", List.of("notification:send"), null, 168);
        RotateApiKeyRequest invalidReq = new RotateApiKeyRequest("key", List.of("notification:send"), null, 169);
        RotateApiKeyRequest negativeReq = new RotateApiKeyRequest("key", List.of("notification:send"), null, -1);

        assertThat(validator.validate(validReq)).isEmpty();
        assertThat(validator.validate(invalidReq))
                .anyMatch(v -> v.getPropertyPath().toString().equals("gracePeriodHours"));
        assertThat(validator.validate(negativeReq))
                .anyMatch(v -> v.getPropertyPath().toString().equals("gracePeriodHours"));
    }

    @Test
    @DisplayName("/ AuthRateLimitFilter: respects X-Real-IP from trusted proxy and prevents X-Forwarded-For spoofing")
    void authRateLimitFilterResolvesClientIpSafely() {
        RateLimitService mockRateLimit = mock(RateLimitService.class);
        AuthRateLimitFilter filter = new AuthRateLimitFilter(mockRateLimit, Set.of("127.0.0.1", "10.0.0.1"));

        HttpServletRequest trustedRequest = mock(HttpServletRequest.class);
        when(trustedRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        when(trustedRequest.getHeader("X-Real-IP")).thenReturn("203.0.113.50");
        when(trustedRequest.getHeader("X-Forwarded-For")).thenReturn("spoofed.ip, 198.51.100.1");

        String resolvedIp = filter.resolveClientIp(trustedRequest);
        assertThat(resolvedIp).isEqualTo("203.0.113.50");

        // When untrusted, remoteAddr is authoritative
        HttpServletRequest untrustedRequest = mock(HttpServletRequest.class);
        when(untrustedRequest.getRemoteAddr()).thenReturn("192.168.1.100");
        when(untrustedRequest.getHeader("X-Real-IP")).thenReturn("203.0.113.50");

        String untrustedResolved = filter.resolveClientIp(untrustedRequest);
        assertThat(untrustedResolved).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("/ GlobalExceptionHandler: populates X-RateLimit headers on HTTP 429")
    void globalExceptionHandlerSetsRateLimitHeaders() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        RateLimitException ex = new RateLimitException(30, 100, 0, 30);

        ResponseEntity<ProblemDetail> response = handler.handleRateLimitException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("100");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeaders().getFirst("X-RateLimit-Reset")).isEqualTo("30");
    }

    @Test
    @DisplayName("/ RefreshTokenService: cross-principal token rotation rejected without revoking token")
    void crossPrincipalRefreshRejectedWithoutRevokingToken() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);
        RefreshTokenService service = new RefreshTokenService(repository, null, clock);

        RefreshTokenEntity adminToken = new RefreshTokenEntity();
        adminToken.setId(UUID.randomUUID());
        adminToken.setPrincipalType(PrincipalType.ADMIN);
        adminToken.setPrincipalId(UUID.randomUUID());
        adminToken.setFamilyId(UUID.randomUUID());
        adminToken.setExpiresAt(Instant.parse("2026-11-04T12:00:00Z"));
        adminToken.setRevokedAt(null);

        when(repository.findByTokenHashWithLock(any())).thenReturn(Optional.of(adminToken));

        // When submitted to tenant refresh (expected TENANT_USER)
        assertThatThrownBy(() ->
                service.rotateRefreshToken("raw-admin-token", PrincipalType.TENANT_USER, "agent", "127.0.0.1")
        ).isInstanceOf(NotifyException.class)
         .hasMessageContaining("Use /auth/refresh for tenant tokens");

        // The token must NOT be revoked
        assertThat(adminToken.getRevokedAt()).isNull();
        verify(repository, never()).save(any());
        verify(repository, never()).revokeFamily(any(), any());
    }
}

