package com.notify.ratelimit;

import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.service.RateLimitService;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitRedisIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RateLimitService rateLimitService;
    private final List<String> testKeysToCleanup = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 6379);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);

        TenantRepository tenantRepository = Mockito.mock(TenantRepository.class);
        rateLimitService = new RateLimitService(tenantRepository, redisTemplate, Clock.systemUTC());
        rateLimitService.init();
    }

    @AfterEach
    void tearDown() {
        for (String key : testKeysToCleanup) {
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("First increment establishes 60-second expiry")
    void firstIncrement_establishesExpiry() {
        String testIp = "198.51.100." + (int) (Math.random() * 200 + 10);
        String route = "/auth/login-test-" + UUID.randomUUID();
        String key = "rl:ip:" + testIp + ":" + route;
        testKeysToCleanup.add(key);

        rateLimitService.checkIpLimit(testIp, route, 30);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(50L, 60L);
    }

    @Test
    @DisplayName("Subsequent increments retain the original window and do not extend it")
    void subsequentIncrements_retainOriginalWindow() throws Exception {
        String testIp = "198.51.100." + (int) (Math.random() * 200 + 10);
        String route = "/auth/login-test-" + UUID.randomUUID();
        String key = "rl:ip:" + testIp + ":" + route;
        testKeysToCleanup.add(key);

        // First increment
        rateLimitService.checkIpLimit(testIp, route, 30);
        Long ttl1 = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl1).isNotNull();

        Thread.sleep(1100); // 1.1s elapsed

        // Second increment
        rateLimitService.checkIpLimit(testIp, route, 30);
        Long ttl2 = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl2).isNotNull();

        // ttl2 must be less than or equal to ttl1, proving window was NOT reset to 60
        assertThat(ttl2).isLessThanOrEqualTo(ttl1);
        assertThat(ttl2).isLessThan(60L);
    }

    @Test
    @DisplayName("A pre-existing counter without TTL is repaired with 60-second expiry")
    void preExistingCounterWithoutTtl_isRepaired() {
        String testIp = "198.51.100." + (int) (Math.random() * 200 + 10);
        String route = "/auth/login-test-" + UUID.randomUUID();
        String key = "rl:ip:" + testIp + ":" + route;
        testKeysToCleanup.add(key);

        // Simulate an unexpired counter from an earlier interrupted operation
        redisTemplate.opsForValue().set(key, "5");
        redisTemplate.persist(key); // Remove any TTL (TTL = -1)
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isEqualTo(-1L);

        // Calling checkIpLimit must repair the missing TTL
        rateLimitService.checkIpLimit(testIp, route, 30);

        Long repairedTtl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(repairedTtl).isNotNull();
        assertThat(repairedTtl).isBetween(50L, 60L);

        String count = redisTemplate.opsForValue().get(key);
        assertThat(count).isEqualTo("6");
    }

    @Test
    @DisplayName("Exhaustion returns 429 and requests recover after window expiry")
    void exhaustionReturns429_andRecoversAfterWindow() {
        String testIp = "198.51.100." + (int) (Math.random() * 200 + 10);
        String route = "/auth/login-test-" + UUID.randomUUID();
        String key = "rl:ip:" + testIp + ":" + route;
        testKeysToCleanup.add(key);

        // Pre-fill counter to 30
        redisTemplate.opsForValue().set(key, "30");
        redisTemplate.expire(key, 60, TimeUnit.SECONDS);

        // 31st request exceeds limit of 30
        assertThatThrownBy(() -> rateLimitService.checkIpLimit(testIp, route, 30))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getRetryAfterSeconds()).isBetween(1L, 60L);
                });

        // Set short TTL to simulate window expiry
        redisTemplate.expire(key, 1, TimeUnit.SECONDS);
        try {
            Thread.sleep(1200);
        } catch (InterruptedException ignored) {
        }

        // Must recover and succeed
        rateLimitService.checkIpLimit(testIp, route, 30);
        String newCount = redisTemplate.opsForValue().get(key);
        assertThat(newCount).isEqualTo("1");
    }
}
