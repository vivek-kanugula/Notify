package com.notify.idempotency.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationStatusHistoryRepository statusHistoryRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyService idempotencyService;
    private final UUID tenantId = UUID.randomUUID();
    private final String idempotencyKey = "test-idem-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(
                notificationRepository,
                statusHistoryRepository,
                redisTemplate
        );
    }

    @Test
    @DisplayName("acquireInFlightLock: sets IN_PROGRESS lock with unique token and 60s TTL")
    void acquireInFlightLock_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(Boolean.TRUE);

        String token = idempotencyService.acquireInFlightLock(tenantId, idempotencyKey);

        assertThat(token).isNotNull();
        assertThat(UUID.fromString(token)).isNotNull(); // Valid UUID token
    }

    @Test
    @DisplayName("acquireInFlightLock: throws 409 IDEMPOTENCY_IN_PROGRESS when lock already held")
    void acquireInFlightLock_alreadyHeld_throwsConflict() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(anyString()))
                .thenReturn("{\"status\":\"IN_PROGRESS\",\"lockToken\":\"other-token\"}");

        assertThatThrownBy(() -> idempotencyService.acquireInFlightLock(tenantId, idempotencyKey))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
                });
    }

    @Test
    @DisplayName("evictInFlightLock: executes Lua script passing cache key and exact lockToken")
    void evictInFlightLock_executesLuaScriptWithToken() {
        String token = UUID.randomUUID().toString();
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(token)))
                .thenReturn(1L);

        idempotencyService.evictInFlightLock(tenantId, idempotencyKey, token);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), eq(token));
        assertThat(keysCaptor.getValue()).containsExactly("idem:" + tenantId + ":" + idempotencyKey);
    }

    @Test
    @DisplayName("evictInFlightLock: fails silently if Redis throws connection exception")
    void evictInFlightLock_handlesRedisExceptionSilently() {
        String token = UUID.randomUUID().toString();
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq(token)))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        // Must not throw
        idempotencyService.evictInFlightLock(tenantId, idempotencyKey, token);
    }

    @Test
    @DisplayName("evictInFlightLock: no-op when lockToken or key is null")
    void evictInFlightLock_nullTokenOrKey_isNoop() {
        idempotencyService.evictInFlightLock(tenantId, idempotencyKey, null);
        idempotencyService.evictInFlightLock(tenantId, null, "some-token");

        verify(redisTemplate, never()).execute(any(), anyList(), any());
    }
}
