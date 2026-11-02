package com.notify.outage;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.common.model.TenantStatus;
import com.notify.idempotency.service.IdempotencyService;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import com.notify.ratelimit.model.RateLimitResult;
import com.notify.ratelimit.service.RateLimitService;
import com.notify.sse.config.SseProperties;
import com.notify.sse.token.StreamTokenService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * / -2: Redis is a disposable cache (). This drives the four policies that decide whether losing
 * it is survivable, against a Redis container this suite owns and then <b>stops</b> mid-test.
 *
 * <table>
 *   <caption>policy under a Redis outage</caption>
 *   <tr><th>Surface</th><th>Required behaviour</th></tr>
 *   <tr><td>Per-tenant rate limiter</td><td>fails <b>open</b> — traffic is admitted, no token deducted</td></tr>
 *   <tr><td>API-key / tenant-status cache</td><td>falls back to <b>PostgreSQL</b>, which is authoritative</td></tr>
 *   <tr><td>Durable idempotency</td><td>falls back to PostgreSQL; the unique index is the real guarantee</td></tr>
 *   <tr><td>SSE stream token</td><td>fails <b>closed</b> — no token can be minted without Redis</td></tr>
 * </table>
 *
 * <p>The container is private to this class, so stopping it cannot disturb the shared containers other
 * suites use. The Redis-backed services are constructed directly against that container rather than taken
 * from the Spring context, because the {@code test} profile excludes Redis auto-configuration entirely — so
 * a context-wired variant would prove nothing about an outage. Repositories come from the context, so the
 * PostgreSQL fallbacks are real database reads.
 *
 * <p>Method order is fixed: the healthy controls must run before the outage, because the outage is
 * irreversible within the class.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisOutageDegradedReliabilityTest extends AbstractPostgresIntegrationTest {

    private static GenericContainer<?> redis;

    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationStatusHistoryRepository statusHistoryRepository;
    @Autowired private SseProperties sseProperties;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static UUID tenantId;

    private RateLimitService rateLimitService;
    private ApiKeyStatusCacheService apiKeyCache;
    private IdempotencyService idempotencyService;
    private StreamTokenService streamTokenService;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();

        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        // Bounded by construction: Lettuce's defaults made each post-outage command wait long enough that the
        // first version of this suite took 457 s. A short command timeout keeps the outage phase to seconds,
        // which is what "bounded supported-outage test" has to mean.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ZERO)
                .build();
        connectionFactory = new LettuceConnectionFactory(config, clientConfig);
        connectionFactory.setValidateConnection(false);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null && redis.isRunning()) {
            redis.stop();
        }
    }

    @BeforeEach
    void setUp() {
        if (tenantId == null) {
            tenantId = tenantRepository.save(
                    new TenantEntity("RedisOutageOrg", "redis-outage-" + UUID.randomUUID())).getId();
        }
        rateLimitService = new RateLimitService(tenantRepository, redisTemplate, Clock.systemUTC());
        rateLimitService.init();
        apiKeyCache = new ApiKeyStatusCacheService(redisTemplate, apiKeyRepository, tenantRepository);
        idempotencyService = new IdempotencyService(
                notificationRepository, statusHistoryRepository, redisTemplate);
        streamTokenService = new StreamTokenService(redisTemplate, sseProperties);
    }

    @Test
    @Order(1)
    @DisplayName("control: with Redis healthy the limiter deducts a token and a stream token is minted")
    void healthyControls() {
        RateLimitResult result = rateLimitService.consumeToken(tenantId);
        assertThat(result.allowed()).isTrue();
        assertThat(result.tokenDeducted())
                .as("a healthy limiter actually deducts, which is what distinguishes it from fail-open")
                .isTrue();

        Optional<String> token = streamTokenService.mint(tenantId, "recipient-healthy");
        assertThat(token)
                .as("a healthy Redis must be able to mint a single-use stream token")
                .isPresent();
    }

    @Test
    @Order(2)
    @DisplayName("with Redis down the rate limiter fails open and the stream token fails closed")
    void outageFailsOpenForLimiterAndClosedForStreamToken() {
        redis.stop();

        RateLimitResult result = rateLimitService.consumeToken(tenantId);
        assertThat(result.allowed())
                .as("the limiter must admit traffic rather than reject it when Redis is gone")
                .isTrue();
        assertThat(result.tokenDeducted())
                .as("nothing was deducted, so this is fail-open and not a genuine grant")
                .isFalse();

        assertThat(streamTokenService.mint(tenantId, "recipient-outage"))
                .as("a stream token is an authorization artifact: without Redis it must not be issued")
                .isEmpty();
        assertThat(streamTokenService.consume("some-token-value"))
                .as("and no token may be accepted either")
                .isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("with Redis down the API-key cache and idempotency fall back to PostgreSQL")
    void outageFallsBackToPostgres() {
        if (redis.isRunning()) {
            redis.stop();
        }

        // Authoritative fallback: the tenant status still resolves, straight from the database.
        assertThat(apiKeyCache.getTenantStatus(tenantId))
                .as("tenant status must still resolve from PostgreSQL when the cache is unavailable")
                .contains(TenantStatus.ACTIVE);

        // An unknown key hash is a clean miss rather than an error surfaced to the caller.
        assertThatCode(() -> assertThat(apiKeyCache.getApiKey("unknown-hash-" + UUID.randomUUID())).isEmpty())
                .as("a cache lookup during an outage degrades to a miss, never an exception")
                .doesNotThrowAnyException();

        // Durable idempotency: PostgreSQL is the guarantee, so a first-seen key is simply not a replay.
        assertThatCode(() -> assertThat(
                idempotencyService.checkReplay(tenantId, "idem-" + UUID.randomUUID(), "hash-abc")).isEmpty())
                .as("idempotency must consult PostgreSQL and not fail the request when Redis is down")
                .doesNotThrowAnyException();
    }
}
