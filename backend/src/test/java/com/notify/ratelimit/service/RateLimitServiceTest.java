package com.notify.ratelimit.service;

import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.model.RateLimitResult;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private RateLimitService rateLimitService;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(tenantRepository, redisTemplate, clock);
        rateLimitService.init();

        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setRateLimitPerMinute(100);
        lenient().when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    }

    @Test
    @DisplayName("consumeToken: when tokens available, returns allowed result with remaining count")
    void consumeToken_whenAllowed_returnsAllowedResult() {
        // Lua returns [allowed (1), limit (100), remaining (99), resetSeconds (1), retryAfter (0)]
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("100"), any(), any(), eq("1"), eq("120")))
                .thenReturn(List.of(1L, 100L, 99L, 1L, 0L));

        RateLimitResult result = rateLimitService.consumeToken(tenantId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(100);
        assertThat(result.remaining()).isEqualTo(99);
        assertThat(result.resetSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("consumeToken: when bucket empty, throws RateLimitException (429) with Retry-After")
    void consumeToken_whenExhausted_throwsRateLimitException() {
        // Lua returns [allowed (0), limit (100), remaining (0), resetSeconds (45), retryAfter (45)]
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("100"), any(), any(), eq("1"), eq("120")))
                .thenReturn(List.of(0L, 100L, 0L, 45L, 45L));

        assertThatThrownBy(() -> rateLimitService.consumeToken(tenantId))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(45L);
                });
    }

    @Test
    @DisplayName("consumeToken: fails open when Redis throws an exception ()")
    void consumeToken_whenRedisFails_failsOpen() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect"));

        RateLimitResult result = rateLimitService.consumeToken(tenantId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(100);
        assertThat(result.remaining()).isEqualTo(100);
    }

    @Test
    @DisplayName("consumeToken: fails open when RedisTemplate is null")
    void consumeToken_whenRedisNull_failsOpen() {
        RateLimitService noRedisService = new RateLimitService(tenantRepository, null, clock);
        noRedisService.init();

        RateLimitResult result = noRedisService.consumeToken(tenantId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(100);
        assertThat(result.remaining()).isEqualTo(100);
    }

    @Test
    @DisplayName("checkIpLimit: throws RateLimitException when count exceeds limitPerMinute")
    void checkIpLimit_whenCountExceedsLimit_throwsRateLimitException() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("rl:ip:192.168.1.1:/auth/login")), eq("60")))
                .thenReturn(List.of(11L, 45L));

        assertThatThrownBy(() -> rateLimitService.checkIpLimit("192.168.1.1", "/auth/login", 10))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(45L);
                });
    }

    @Test
    @DisplayName("checkIpLimit: succeeds when count is within limit")
    void checkIpLimit_whenWithinLimit_succeeds() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("rl:ip:192.168.1.1:/auth/login")), eq("60")))
                .thenReturn(List.of(5L, 50L));

        // Should not throw
        rateLimitService.checkIpLimit("192.168.1.1", "/auth/login", 10);
    }

    @Test
    @DisplayName("checkIpLimit: fails open when Redis is unavailable")
    void checkIpLimit_whenRedisFails_failsOpen() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        // Should not throw
        rateLimitService.checkIpLimit("192.168.1.1", "/auth/login", 10);
    }

    @Test
    @DisplayName("consumeToken: increments throttled metric when bucket is exhausted")
    void consumeToken_whenExhausted_incrementsThrottledMetric() {
        com.notify.observability.metrics.NotifyMetrics mockMetrics = org.mockito.Mockito.mock(com.notify.observability.metrics.NotifyMetrics.class);
        RateLimitService serviceWithMetrics = new RateLimitService(tenantRepository, redisTemplate, clock, mockMetrics);
        serviceWithMetrics.init();

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("100"), any(), any(), eq("1"), eq("120")))
                .thenReturn(List.of(0L, 100L, 0L, 45L, 45L));

        assertThatThrownBy(() -> serviceWithMetrics.consumeToken(tenantId))
                .isInstanceOf(RateLimitException.class);

        verify(mockMetrics).incrementThrottled("TENANT", tenantId);
    }

    @Test
    @DisplayName("checkIpLimit: increments throttled metric when IP limit is exceeded")
    void checkIpLimit_whenExceeded_incrementsThrottledMetric() {
        com.notify.observability.metrics.NotifyMetrics mockMetrics = org.mockito.Mockito.mock(com.notify.observability.metrics.NotifyMetrics.class);
        RateLimitService serviceWithMetrics = new RateLimitService(tenantRepository, redisTemplate, clock, mockMetrics);
        serviceWithMetrics.init();

        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("rl:ip:192.168.1.1:/auth/login")), eq("60")))
                .thenReturn(List.of(15L, 30L));

        assertThatThrownBy(() -> serviceWithMetrics.checkIpLimit("192.168.1.1", "/auth/login", 10))
                .isInstanceOf(RateLimitException.class);

        verify(mockMetrics).incrementThrottled("IP", null);
    }
}
