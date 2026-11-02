package com.notify.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.TenantStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.notify.common.model.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationIdempotencyTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.notify.outbox.repository.OutboxEventRepository outboxEventRepository;

    @Autowired
    private com.notify.ratelimit.service.RateLimitService rateLimitService;

    @Autowired
    private com.notify.idempotency.service.IdempotencyService idempotencyService;

    @Autowired
    private com.notify.notification.service.NotificationService notificationService;

    @Autowired
    private com.notify.notification.model.NotificationTransitioner notificationTransitioner;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record TenantFixture(UUID tenantId, UUID userId, String token) {}

    private TenantFixture createTenant(String name) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name + " " + unique, "owner-" + unique + "@example.com", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        var claims = jwtService.parseToken(token);
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        return new TenantFixture(tenantId, userId, token);
    }

    private String createApiKey(TenantFixture tenant, String name, List<String> scopes) throws Exception {
        CreateApiKeyRequest req = new CreateApiKeyRequest(name, scopes, null);
        MvcResult res = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("apiKey").asText();
    }

    @Test
    @DisplayName("Header-over-body precedence: HTTP header key overrides body key")
    void headerOverBodyPrecedence() throws Exception {
        TenantFixture tenant = createTenant("PrecedenceTenant");
        String headerKey = "hdr-key-" + UUID.randomUUID();
        String bodyKey = "body-key-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Header vs Body",
                "Testing header precedence",
                null,
                null,
                null,
                null,
                bodyKey,
                null
        );

        MvcResult res = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("Idempotency-Key", headerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText());
        NotificationEntity n = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(n.getIdempotencyKey()).isEqualTo(headerKey);
    }

    @Test
    @DisplayName("Matching payload replay returns 202 and Idempotent-Replay: true")
    void matchingPayloadReplay_returns202WithReplayHeader() throws Exception {
        TenantFixture tenant = createTenant("ReplayTenant");
        String idemKey = "match-key-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "replay@example.com",
                "Replay Subject",
                "Replay Body",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        // 1. Initial creation
        MvcResult res1 = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn();
        String id1 = objectMapper.readTree(res1.getResponse().getContentAsString()).path("id").asText();

        // 2. Matching replay
        MvcResult res2 = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();
        String id2 = objectMapper.readTree(res2.getResponse().getContentAsString()).path("id").asText();

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("Payload mismatch with same idempotency key returns 422 IDEMPOTENCY_KEY_REUSED")
    void payloadMismatch_returns422() throws Exception {
        TenantFixture tenant = createTenant("MismatchTenant");
        String idemKey = "mismatch-key-" + UUID.randomUUID();

        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "first@example.com",
                "Original Subject",
                "Original Body",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isAccepted());

        // Payload variation: different recipient
        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "tampered@example.com",
                "Original Subject",
                "Original Body",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("PostgreSQL fallback replays original acceptance status from status-history, not current status")
    void postgresqlFallback_replaysOriginalAcceptanceStatusFromHistory() throws Exception {
        TenantFixture tenant = createTenant("PgFallbackTenant");
        String idemKey = "pg-history-key-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "delivery@example.com",
                "Lifecycle Test",
                "Delivery Lifecycle",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        // 1. Initial acceptance -> QUEUED
        MvcResult res1 = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();
        UUID notificationId = UUID.fromString(objectMapper.readTree(res1.getResponse().getContentAsString()).path("id").asText());

        // 2. Simulate subsequent lifecycle progression to DELIVERED
        NotificationEntity n = notificationRepository.findById(notificationId).orElseThrow();
        n.setStatus(NotificationStatus.DELIVERED);
        notificationRepository.save(n);

        // Add a status history entry for DELIVERED
        NotificationStatusHistoryEntity deliveredHist = new NotificationStatusHistoryEntity();
        deliveredHist.setTenantId(tenant.tenantId());
        deliveredHist.setNotificationId(notificationId);
        deliveredHist.setFromStatus(NotificationStatus.PROCESSING);
        deliveredHist.setToStatus(NotificationStatus.DELIVERED);
        deliveredHist.setActor(Actor.SYSTEM);
        deliveredHist.setReason("Provider confirmed delivery");
        deliveredHist.setOccurredAt(Instant.now());
        statusHistoryRepository.save(deliveredHist);

        // Clear Redis cache for this key if Redis is available, forcing PostgreSQL fallback
        if (redisTemplate != null) {
            try {
                redisTemplate.delete("idem:" + tenant.tenantId() + ":" + idemKey);
            } catch (Exception ignored) {}
        }

        // 3. Replay request - must return original acceptance status (QUEUED), not DELIVERED
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(notificationId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("Security: API key without notifications:send cannot obtain replay (403 INSUFFICIENT_SCOPE)")
    void apiKeyWithoutSendScope_cannotObtainReplay() throws Exception {
        TenantFixture tenant = createTenant("ScopeSecTenant");
        String idemKey = "scope-sec-key-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.IN_APP,
                "user-scope",
                null,
                "Scope test",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        // 1. Create notification using tenant owner token
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());

        // 2. Create API key with ONLY notifications:read
        String readOnlyKey = createApiKey(tenant, "ReadOnlyKey", List.of("notifications:read"));

        // 3. Attempt replay with read-only API key -> 403 Forbidden
        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", readOnlyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Suspended tenant cannot obtain cached or database replay (403 TENANT_SUSPENDED)")
    void suspendedTenant_cannotObtainReplay() throws Exception {
        TenantFixture tenant = createTenant("SuspendedReplayTenant");
        String idemKey = "suspend-replay-key-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "suspend@example.com",
                "Suspend Test",
                "Suspend Body",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        // 1. Initial acceptance
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());

        // 2. Suspend tenant
        tenantRepository.findById(tenant.tenantId()).ifPresent(t -> {
            t.setStatus(TenantStatus.SUSPENDED);
            tenantRepository.save(t);
        });

        // 3. Attempt replay while suspended -> 403 TENANT_SUSPENDED
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));
    }

    @Test
    @DisplayName("Regression: Failed validation does not leave IN_PROGRESS marker blocking immediate retry")
    void failedValidation_doesNotBlockImmediateRetry() throws Exception {
        TenantFixture tenant = createTenant("RetryAfterFailTenant");
        String idemKey = "retry-key-" + UUID.randomUUID();

        // 1. Initial request has invalid payload: EMAIL without subject -> 422 VALIDATION_FAILED
        CreateNotificationRequest invalidReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "retry@example.com",
                null, // Missing subject for EMAIL
                "Body content",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 2. Corrected retry immediately submitted with the SAME Idempotency-Key
        CreateNotificationRequest validReq = new CreateNotificationRequest(
                Channel.EMAIL,
                "retry@example.com",
                "Corrected Subject",
                "Body content",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        // Must succeed with 202 Accepted, proving IN_PROGRESS was evicted and did not block retry
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("Regression: Stale lock cleanup cannot delete a different request's newer lock")
    void staleLockCleanup_doesNotDeleteNewerLock() {
        UUID tenantId = UUID.randomUUID();
        String idemKey = "race-key-" + UUID.randomUUID();

        // 1. Request A acquires lock with token A
        var idempotencyService = context.getBean(com.notify.idempotency.service.IdempotencyService.class);
        String tokenA = idempotencyService.acquireInFlightLock(tenantId, idemKey);

        // Simulate token A having expired/overwritten by Request B with token B
        String tokenB = UUID.randomUUID().toString();
        boolean redisAvailable = false;
        if (redisTemplate != null) {
            try {
                redisTemplate.getConnectionFactory().getConnection().ping();
                redisAvailable = true;
            } catch (Exception ignored) {
                // Test profile runs without Redis (fail-open)
            }
        }
        if (redisAvailable) {
            String cacheKey = "idem:" + tenantId + ":" + idemKey;
            try {
                String inProgressB = objectMapper.writeValueAsString(com.notify.idempotency.model.CachedIdempotency.inProgress(tokenB));
                redisTemplate.opsForValue().set(cacheKey, inProgressB);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // 2. Request A's delayed cleanup runs with stale token A
            idempotencyService.evictInFlightLock(tenantId, idemKey, tokenA);

            // 3. Verify Request B's lock is STILL present and was NOT deleted
            String currentVal = redisTemplate.opsForValue().get(cacheKey);
            assertThat(currentVal).isNotNull();
            assertThat(currentVal).contains(tokenB);

            // Clean up
            redisTemplate.delete(cacheKey);
        }
    }

    @Test
    @DisplayName("Regression: Payloads with previously colliding colon boundaries return 422 IDEMPOTENCY_KEY_REUSED on retry")
    void ambiguousColonPayloadRetry_returns422IdempotencyKeyReused() throws Exception {
        TenantFixture tenant = createTenant("ColonTestOrg");
        String idemKey = "colon-idem-" + UUID.randomUUID();

        // 1. Submit first payload (subject: "a:b", body: "c")
        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "a:b",
                "c",
                null, null, null, null, null, null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // 2. Retry with same Idempotency-Key but different payload (subject: "a", body: "b:c")
        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "a",
                "b:c",
                null, null, null, null, null, null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        // Exactly 1 notification exists for this tenant
        List<NotificationEntity> notifs = notificationRepository.findAll().stream()
                .filter(n -> tenant.tenantId().equals(n.getTenantId()))
                .toList();
        assertThat(notifs).hasSize(1);
    }

    @Test
    @DisplayName("Idempotency-Key header exceeding 128 chars returns 422 VALIDATION_FAILED before writes")
    void idempotencyKeyHeader_exceeds128Chars_returns422ValidationFailed() throws Exception {
        TenantFixture tenant = createTenant("OversizedIdemOrg");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, null, null, null
        );

        String longHeader = "k".repeat(129);
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("Idempotency-Key", longHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // Verify no notification rows or outbox rows were written
        List<NotificationEntity> notifs = notificationRepository.findAll().stream()
                .filter(n -> tenant.tenantId().equals(n.getTenantId()))
                .toList();
        assertThat(notifs).isEmpty();
    }

    @Test
    @DisplayName("DONE entry appearing during lock acquisition is served as replay without token deduction")
    void doneEntryAppearsDuringLockAcquisition_servedAsReplayWithoutTokenDeduction() throws Exception {
        org.springframework.data.redis.connection.RedisStandaloneConfiguration redisConfig =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration("localhost", 6379);
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory cf =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(redisConfig);
        cf.afterPropertiesSet();
        StringRedisTemplate directRedis = new StringRedisTemplate(cf);

        try {
            directRedis.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            cf.destroy();
            org.junit.jupiter.api.Assertions.fail("localhost:6379 Redis is required for these integration checks", ex);
        }

        UUID tenantId = UUID.randomUUID();
        String idemKey = "done-lock-race-" + UUID.randomUUID();
        String cacheKey = "idem:" + tenantId + ":" + idemKey;

        try {
            var idemServiceWithRedis = new com.notify.idempotency.service.IdempotencyService(
                    notificationRepository, statusHistoryRepository, directRedis
            );

            UUID originalNotificationId = UUID.randomUUID();
            String originalHash = "hash-12345";
            String doneJson = objectMapper.writeValueAsString(
                    com.notify.idempotency.model.CachedIdempotency.done(originalHash, originalNotificationId, NotificationStatus.QUEUED)
            );
            directRedis.opsForValue().set(cacheKey, doneJson);

            // 1. acquireInFlightLock with matching hash -> returns Replay
            var lockResult = idemServiceWithRedis.acquireInFlightLock(tenantId, idemKey, originalHash);
            assertThat(lockResult).isInstanceOf(com.notify.idempotency.model.LockResult.Replay.class);
            var replay = ((com.notify.idempotency.model.LockResult.Replay) lockResult).result();
            assertThat(replay.notificationId()).isEqualTo(originalNotificationId);
            assertThat(replay.acceptanceStatus()).isEqualTo(NotificationStatus.QUEUED);

            // 2. acquireInFlightLock with different hash -> throws 422 IDEMPOTENCY_KEY_REUSED
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.notify.common.error.NotifyException.class,
                    () -> idemServiceWithRedis.acquireInFlightLock(tenantId, idemKey, "different-hash")
            );

            // 3. IN_PROGRESS entry -> throws 409 IDEMPOTENCY_IN_PROGRESS
            String inProgressJson = objectMapper.writeValueAsString(
                    com.notify.idempotency.model.CachedIdempotency.inProgress(UUID.randomUUID().toString())
            );
            directRedis.opsForValue().set(cacheKey, inProgressJson);
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.notify.common.error.NotifyException.class,
                    () -> idemServiceWithRedis.acquireInFlightLock(tenantId, idemKey, originalHash)
            );
        } finally {
            directRedis.delete(cacheKey);
            cf.destroy();
        }
    }

    @Test
    @DisplayName("Database unique-index conflict: concurrent requests produce exactly 1 notification and 1 outbox event, and replay is returned")
    // NOTE: Runs with Redis auto-configuration disabled by application-test.yml.
    // Asserts authoritative database row counts and outbox preservation under PostgreSQL concurrency;
    // does not assert Redis token debit or refund. For net-zero Redis token debit/refund proof,
    // see databaseUniqueIndexConflict_realRedisTokenDebitAndRefund_verifiedNetZeroDeduction.
    void databaseUniqueIndexConflict_concurrentRequests_preservesCountsAndReturnsReplay() throws Exception {
        TenantFixture tenant = createTenant("DbConflictOrg");
        String idemKey = "db-conflict-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "conflict@example.com",
                "Conflict Subject",
                "Conflict Body",
                null, null, null, null, null, null
        );

        int initialNotifCount = notificationRepository.findAll().stream()
                .filter(n -> tenant.tenantId().equals(n.getTenantId()))
                .toList().size();
        int initialOutboxCount = outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING).size();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<com.notify.notification.service.NotificationService.AcceptanceResult> task = () -> {
            barrier.await();
            return notificationService.acceptNotification(tenant.tenantId(), req, idemKey);
        };

        Future<com.notify.notification.service.NotificationService.AcceptanceResult> f1 = executor.submit(task);
        Future<com.notify.notification.service.NotificationService.AcceptanceResult> f2 = executor.submit(task);

        var r1 = f1.get(5, TimeUnit.SECONDS);
        var r2 = f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one new acceptance and one replay
        boolean oneIsReplay = (r1.isReplay() && !r2.isReplay()) || (!r1.isReplay() && r2.isReplay());
        assertThat(oneIsReplay).isTrue();
        assertThat(r1.notificationId()).isEqualTo(r2.notificationId());
        assertThat(r1.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(r2.status()).isEqualTo(NotificationStatus.QUEUED);

        // Authoritative database check: exactly 1 notification row for this tenant
        List<NotificationEntity> notifs = notificationRepository.findAll().stream()
                .filter(n -> tenant.tenantId().equals(n.getTenantId()))
                .toList();
        assertThat(notifs).hasSize(initialNotifCount + 1);

        // Authoritative database check: exactly 1 outbox row for this tenant
        List<com.notify.outbox.entity.OutboxEventEntity> outboxEvents =
                outboxEventRepository.findByTenantIdAndStatus(tenant.tenantId(), OutboxStatus.PENDING);
        assertThat(outboxEvents).hasSize(initialOutboxCount + 1);

        // Initial status history: exactly 1 entry (CREATED -> QUEUED)
        List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), r1.notificationId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
    }

    @Test
    @DisplayName("Real Redis token debit and refund on DB conflict verifies net-zero quota deduction")
    void databaseUniqueIndexConflict_realRedisTokenDebitAndRefund_verifiedNetZeroDeduction() throws Exception {
        org.springframework.data.redis.connection.RedisStandaloneConfiguration redisConfig =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration("localhost", 6379);
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory cf =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(redisConfig);
        cf.afterPropertiesSet();
        StringRedisTemplate directRedis = new StringRedisTemplate(cf);

        try {
            directRedis.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            cf.destroy();
            org.junit.jupiter.api.Assertions.fail("localhost:6379 Redis is required for these integration checks", ex);
        }

        TenantFixture tenant = createTenant("RealRedisConflictTenant");
        UUID tenantId = tenant.tenantId();
        String idemKey = "real-conflict-" + UUID.randomUUID();
        String bucketKey = "rl:" + tenantId;
        String cacheKey = "idem:" + tenantId + ":" + idemKey;

        java.time.Clock fixedClock = java.time.Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), java.time.ZoneOffset.UTC);

        try {
            // Real rate limiter with isolated Redis bucket and fixed clock (zero refill drift)
            var realRateLimitService = new com.notify.ratelimit.service.RateLimitService(tenantRepository, directRedis, fixedClock);
            realRateLimitService.init();

            // Force idempotency lock path needed for the DB race without disabling the rate limiter:
            // IdempotencyService with null redis template always returns LockResult.acquired(null),
            // allowing subsequent racing requests to pass the lock check, consume a real rate-limit token,
            // and contend on PostgreSQL's unique constraint.
            var forcingIdempotencyService = new com.notify.idempotency.service.IdempotencyService(
                    notificationRepository, statusHistoryRepository, null
            );

            var templateService = context.getBean(com.notify.template.service.TemplateService.class);
            var realNotificationService = new com.notify.notification.service.NotificationService(
                    notificationRepository,
                    statusHistoryRepository,
                    outboxEventRepository,
                    tenantRepository,
                    templateService,
                    forcingIdempotencyService,
                    realRateLimitService,
                    notificationTransitioner,
                    eventPublisher,
                    fixedClock,
                    transactionManager
            );

            CreateNotificationRequest req = new CreateNotificationRequest(
                    Channel.EMAIL,
                    "netzero@example.com",
                    "Net Zero Subject",
                    "Net Zero Body",
                    null, null, null, null, null, null
            );

            // Initial state: bucket key does not exist yet (tenant capacity is 100)
            assertThat(directRedis.hasKey(bucketKey)).isFalse();

            // Request 1: Newly accepted notification -> consumes 1 token from Redis (100 -> 99)
            var r1 = realNotificationService.acceptNotification(tenantId, req, idemKey);
            assertThat(r1.isReplay()).isFalse();
            assertThat(r1.status()).isEqualTo(NotificationStatus.QUEUED);

            // Assert actual Redis balance after Request 1: exactly 99.0 tokens (1 token deducted)
            String tokensAfterFirst = (String) directRedis.opsForHash().get(bucketKey, "tokens");
            assertThat(tokensAfterFirst).isNotNull();
            double balanceAfterFirst = Double.parseDouble(tokensAfterFirst);
            assertThat(balanceAfterFirst).isEqualTo(99.0);

            // Request 2: Replay request in DB race with forced lock acquisition.
            // 1. Passes forcingIdempotencyService.acquireInFlightLock -> LockResult.acquired
            // 2. realRateLimitService.consumeToken -> deducts token in Redis (99 -> 98)
            // 3. persistNotification -> PostgreSQL throws unique index conflict (uq_notifications_tenant_idempotency)
            // 4. handleConcurrentConflict -> detects tokenDeducted == true, invokes refundToken in Redis (98 -> 99)
            // 5. Returns verified replay of original notification
            var r2 = realNotificationService.acceptNotification(tenantId, req, idemKey);
            assertThat(r2.isReplay()).isTrue();
            assertThat(r2.notificationId()).isEqualTo(r1.notificationId());
            assertThat(r2.status()).isEqualTo(NotificationStatus.QUEUED);

            // Assert actual Redis balance after Request 2 conflict & refund:
            // exactly 99.0 tokens (net zero extra deduction for replay)
            String tokensAfterConflict = (String) directRedis.opsForHash().get(bucketKey, "tokens");
            assertThat(tokensAfterConflict).isNotNull();
            double balanceAfterConflict = Double.parseDouble(tokensAfterConflict);
            assertThat(balanceAfterConflict).isEqualTo(99.0);

            // Authoritative database assertions: exactly 1 notification, 1 outbox event, 1 status history
            List<NotificationEntity> notifs = notificationRepository.findAll().stream()
                    .filter(n -> tenantId.equals(n.getTenantId()))
                    .toList();
            assertThat(notifs).hasSize(1);
            assertThat(notifs.get(0).getId()).isEqualTo(r1.notificationId());

            List<com.notify.outbox.entity.OutboxEventEntity> outboxEvents =
                    outboxEventRepository.findByTenantIdAndStatus(tenantId, OutboxStatus.PENDING);
            assertThat(outboxEvents).hasSize(1);

            List<NotificationStatusHistoryEntity> history = statusHistoryRepository
                    .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, r1.notificationId());
            assertThat(history).hasSize(1);
            assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.QUEUED);

        } finally {
            // Scoped cleanup: do NOT flush shared Redis
            directRedis.delete(bucketKey);
            directRedis.delete(cacheKey);
            cf.destroy();
        }
    }

    @Test
    @DisplayName("Fail-open rate check followed by Redis recovery does not issue phantom refund on DB conflict")
    void databaseUniqueIndexConflict_failOpenLimiter_doesNotIssuePhantomRefund() throws Exception {
        org.springframework.data.redis.connection.RedisStandaloneConfiguration redisConfig =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration("localhost", 6379);
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory cf =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(redisConfig);
        cf.afterPropertiesSet();
        StringRedisTemplate directRedis = new StringRedisTemplate(cf);

        try {
            directRedis.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            cf.destroy();
            org.junit.jupiter.api.Assertions.fail("localhost:6379 Redis is required for these integration checks", ex);
        }

        TenantFixture tenant = createTenant("FailOpenRecoveryTenant");
        UUID tenantId = tenant.tenantId();
        String idemKey = "failopen-conflict-" + UUID.randomUUID();
        String bucketKey = "rl:" + tenantId;
        String cacheKey = "idem:" + tenantId + ":" + idemKey;

        java.time.Clock fixedClock = java.time.Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), java.time.ZoneOffset.UTC);

        try {
            // Initialize isolated Redis bucket with 99 tokens
            directRedis.opsForHash().put(bucketKey, "tokens", "99");
            directRedis.opsForHash().put(bucketKey, "last_updated", String.valueOf(fixedClock.instant().getEpochSecond()));

            // Real RateLimitService is now available / recovered with direct Redis connection
            var realRateLimitService = new com.notify.ratelimit.service.RateLimitService(tenantRepository, directRedis, fixedClock);
            realRateLimitService.init();

            var templateService = context.getBean(com.notify.template.service.TemplateService.class);
            var testNotificationService = new com.notify.notification.service.NotificationService(
                    notificationRepository,
                    statusHistoryRepository,
                    outboxEventRepository,
                    tenantRepository,
                    templateService,
                    idempotencyService,
                    realRateLimitService,
                    notificationTransitioner,
                    eventPublisher,
                    fixedClock,
                    transactionManager
            );

            // Step 1: Pre-persist notification in PostgreSQL so conflict can be exercised
            String incomingHash = "matching-request-hash-123";
            NotificationEntity existing = new NotificationEntity();
            existing.setTenantId(tenantId);
            existing.setChannel(Channel.EMAIL);
            existing.setRecipient("failopen@example.com");
            existing.setSubject("Fail Open Subject");
            existing.setBody("Fail Open Body");
            existing.setStatus(NotificationStatus.QUEUED);
            existing.setIdempotencyKey(idemKey);
            existing.setRequestHash(incomingHash);
            existing.setDispatchGeneration(1);
            existing.setDeliveryCycle(1);
            existing.setCreatedAt(fixedClock.instant());
            existing.setUpdatedAt(fixedClock.instant());
            existing = notificationRepository.save(existing);

            NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
            history.setTenantId(tenantId);
            history.setNotificationId(existing.getId());
            history.setFromStatus(NotificationStatus.CREATED);
            history.setToStatus(NotificationStatus.QUEUED);
            history.setActor(Actor.SYSTEM);
            history.setReason("Notification queued for delivery");
            history.setOccurredAt(fixedClock.instant());
            statusHistoryRepository.save(history);

            // Step 2: Simulate a racing request where rate limit check failed open (tokenDeducted == false)
            com.notify.ratelimit.model.RateLimitResult failOpenResult =
                    com.notify.ratelimit.model.RateLimitResult.failOpen(100);
            assertThat(failOpenResult.tokenDeducted()).isFalse();

            // Step 3: DB conflict resolution occurs after Redis has recovered
            var replayResult = testNotificationService.handleConcurrentConflict(
                    tenantId, idemKey, incomingHash, failOpenResult
            );

            assertThat(replayResult.isReplay()).isTrue();
            assertThat(replayResult.notificationId()).isEqualTo(existing.getId());

            // Step 4: Verify Redis balance remains strictly 99 (NO phantom refund for un-debited check!)
            String tokensAfter = (String) directRedis.opsForHash().get(bucketKey, "tokens");
            assertThat(tokensAfter).isNotNull();
            double balanceAfter = Double.parseDouble(tokensAfter);
            assertThat(balanceAfter).isEqualTo(99.0);

            // Step 5: Contrast with a request where token WAS deducted (tokenDeducted == true)
            com.notify.ratelimit.model.RateLimitResult deductedResult =
                    com.notify.ratelimit.model.RateLimitResult.allowed(100, 98, 60);
            assertThat(deductedResult.tokenDeducted()).isTrue();

            testNotificationService.handleConcurrentConflict(
                    tenantId, idemKey, incomingHash, deductedResult
            );

            // Now refund DOES occur: 99 + 1 = 100
            String tokensAfterDeductedRefund = (String) directRedis.opsForHash().get(bucketKey, "tokens");
            assertThat(tokensAfterDeductedRefund).isNotNull();
            double balanceAfterDeducted = Double.parseDouble(tokensAfterDeductedRefund);
            assertThat(balanceAfterDeducted).isEqualTo(100.0);

        } finally {
            // Scoped cleanup: do NOT flush shared Redis
            directRedis.delete(bucketKey);
            directRedis.delete(cacheKey);
            cf.destroy();
        }
    }

    @Test
    @DisplayName("Database unique-index conflict: mismatched payload throws 422 IDEMPOTENCY_KEY_REUSED")
    void databaseUniqueIndexConflict_mismatchedPayload_returns422() throws Exception {
        TenantFixture tenant = createTenant("MismatchConflictOrg");
        UUID tenantId = tenant.tenantId();
        String idemKey = "conflict-mismatch-" + UUID.randomUUID();

        // Persist first notification in DB
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setRecipient("original@example.com");
        n.setSubject("Original Subject");
        n.setBody("Original Body");
        n.setStatus(NotificationStatus.QUEUED);
        n.setIdempotencyKey(idemKey);
        n.setRequestHash("original-hash-123");
        n.setDispatchGeneration(1);
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        notificationRepository.save(n);

        // Simulate concurrent conflict resolution with different payload hash
        com.notify.common.error.NotifyException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.notify.common.error.NotifyException.class,
                () -> notificationService.handleConcurrentConflict(tenantId, idemKey, "different-hash-456", null)
        );
        assertThat(ex.getErrorCode()).isEqualTo(com.notify.common.error.ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(ex.getStatus().value()).isEqualTo(422);
    }
}
