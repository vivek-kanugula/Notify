package com.notify.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.config.KafkaTopicConfigReconciler;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that when Kafka is down:
 * 1. Application startup completes promptly without the prior ~60-second delay.
 * 2. Readiness probe (/actuator/health/readiness) returns UP (evaluates readinessState and db; excludes kafka/redis).
 * 3. Notification ingress returns 202 ACCEPTED and persists notification (QUEUED) and outbox row (PENDING) atomically.
 * 4. Topic reconciliation succeeds after Kafka broker recovers.
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("deprecation")
class KafkaDownStartupAndIngressTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaDownStartupAndIngressTest.class);

    /**
     * This suite's own PostgreSQL container, and why it does not use the shared one.
     *
     * <p>/ -3: {@code kafkaDown_10RowOutboxRelayBatch_...} seeds ten fresh rows and asserts that the batch
     * advanced <b>all ten</b> to {@code attempts = 1}. On the shared database that assertion cannot hold, and not
     * because of a race: {@link com.notify.outbox.service.OutboxRelayService} splits a batch of 10 into a fresh
     * quota of 7 plus up to 3 due <b>retry</b> rows ({@code attempts > 0}), and tops the fresh side back up only
     * with what the retry side leaves unused. Any leftover due retry row from one of the ~40 suites on the shared
     * containers therefore takes a slot one of this suite's rows needed - measured as exactly two rows left at
     * {@code attempts = 0}. Relaxing the assertion to {@code >= 1} would delete what it proves, that the failure
     * path commits instead of rolling back, so the suite gets a database nobody else writes to.
     *
     * <p>{@link com.notify.AbstractIsolatedDatabaseTest} is deliberately <b>not</b> extended: its
     * {@code @DynamicPropertySource} also registers {@code spring.kafka.bootstrap-servers} and, as that class
     * documents, a parent's registration runs <b>last</b> and overwrites the subclass's - which would point this
     * suite at a live broker and destroy the Kafka-down premise of every assertion here. Holding both
     * registrations in one method is what keeps the dead port dead.
     */
    private static final PostgreSQLContainer<?> ownPostgres;

    // Secondary live Kafka container to simulate broker recovery
    private static final KafkaContainer liveBroker;

    /**
     * Measured duration of the Spring context's own refresh, in milliseconds.
     *
     * <p>/ -3 repair: this was previously wall-clock since a static initializer, which runs when
     * JUnit <b>loads</b> the class - long before the context is built. Any slow suite scheduled in between
     * was therefore counted as "startup", so the assertion failed under the .1 JaCoCo agent and again
     * beside the .4 outage suites, while passing when this class ran alone. The 45 s threshold is
     * unchanged; only the thing being measured is.
     */
    private static volatile long contextStartupMillis = -1L;

    static {
        ownPostgres = new PostgreSQLContainer<>("postgres:16-alpine");
        ownPostgres.start();
        liveBroker = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
        liveBroker.start();
    }

    /** Records how long the application context itself took to refresh. */
    @org.springframework.boot.test.context.TestConfiguration
    static class StartupProbeConfiguration {
        @org.springframework.context.annotation.Bean
        org.springframework.context.ApplicationListener<
                org.springframework.context.event.ContextRefreshedEvent> startupProbe() {
            return event -> contextStartupMillis =
                    event.getTimestamp() - event.getApplicationContext().getStartupDate();
        }
    }

    @DynamicPropertySource
    static void configureOwnDatabaseAndOfflineKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ownPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", ownPostgres::getUsername);
        registry.add("spring.datasource.password", ownPostgres::getPassword);
        // Point application bootstrap servers to unreachable port to simulate Kafka downtime
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:65534");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private com.notify.kafka.producer.KafkaDispatchProducer kafkaDispatchProducer;

    @Autowired
    private java.time.Clock clock;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private com.notify.outbox.service.OutboxRelayService outboxRelayService;

    @Autowired
    private KafkaTopicConfigReconciler topicReconciler;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private UUID tenantId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        // Each test starts from an empty outbox queue. The relay fetch is global and quota-split, so one
        // leftover fresh row from the other test in this class (older created_at) consumes one of the seven
        // fresh-quota slots and would leave one seeded row at attempts = 0. Deleting wholesale is safe: this
        // container belongs to this suite alone.
        outboxEventRepository.deleteAllInBatch();

        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(
                "OfflineKafkaOrg",
                "offline-kafka-" + uniqueSuffix + "@notify.local",
                "Password123!"
        );
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        this.token = objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
        this.tenantId = UUID.fromString(jwtService.parseToken(token).get("tid", String.class));
        this.outboxRelayService = new com.notify.outbox.service.OutboxRelayService(
                outboxEventRepository, kafkaDispatchProducer, clock, transactionManager
        );
    }

    @Test
    @DisplayName("Kafka down: startup does not hang, readiness probe is UP, ingress returns 202, and recovery reconciles topics")
    void kafkaDown_startupDoesNotHang_ingressReturns202_recoveryReconciles() throws Exception {
        long startupElapsed = contextStartupMillis;
        log.info("Kafka-down application context refresh took {} ms", startupElapsed);

        // 1. Prove startup completed without the prior ~60-second hang. Measured from the context's own
        // refresh, so a slow neighbouring suite can never be mistaken for slow startup.
        assertThat(startupElapsed)
                .as("the startup probe must have fired, otherwise the threshold below proves nothing")
                .isPositive();
        assertThat(startupElapsed)
                .as("Startup time with offline Kafka broker should not experience the prior ~60s hang")
                .isLessThan(45_000L);

        // 2. Readiness probe (/actuator/health/readiness) must be UP because it only requires readinessState and db
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka").doesNotExist())
                .andExpect(jsonPath("$.components.redis").doesNotExist());

        // 3. Ingress acceptance: POST /api/v1/notifications succeeds with 202 ACCEPTED
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL,
                "offline-ingress@example.com",
                "Durable Acceptance Subject",
                "Durable Acceptance Body",
                null, null, null, null, null, null
        );

        MvcResult acceptResult = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        UUID notificationId = UUID.fromString(
                objectMapper.readTree(acceptResult.getResponse().getContentAsString()).path("id").asText()
        );

        // 4. Database state: Notification is stored as QUEUED, outbox row is stored with status PENDING
        NotificationEntity entity = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.QUEUED);

        List<OutboxEventEntity> outboxRows = outboxEventRepository.findByTenantIdAndStatus(tenantId, OutboxStatus.PENDING);
        assertThat(outboxRows).hasSize(1);
        OutboxEventEntity outbox = outboxRows.get(0);
        assertThat(outbox.getAggregateId()).isEqualTo(notificationId);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getPublishedAt()).isNull();

        // 5. Initial background reconciliation with Kafka down has not succeeded
        assertThat(topicReconciler.isReconciled()).isFalse();

        // 6. Kafka Recovery: simulate broker recovery by reconciling against live broker
        Properties recoveryProps = new Properties();
        recoveryProps.put("bootstrap.servers", liveBroker.getBootstrapServers());
        recoveryProps.put("default.api.timeout.ms", 3000);
        recoveryProps.put("request.timeout.ms", 2000);

        try (AdminClient liveAdminClient = AdminClient.create(recoveryProps)) {
            // Reconcile topics against recovered broker
            topicReconciler.reconcileTopicConfigs(liveAdminClient);

            // Assert topics exist on the recovered broker
            var topics = liveAdminClient.listTopics().names().get(5, TimeUnit.SECONDS);
            for (KafkaTopicConfigReconciler.TopicSpec spec : KafkaTopicConfigReconciler.EXPECTED_TOPICS) {
                assertThat(topics).contains(spec.name());
            }
        }
    }

    @Test
    @DisplayName("Kafka down: 10-row outbox relay batch completes within safe headroom and commits attempts=1 and backoff without transaction rollback")
    void kafkaDown_10RowOutboxRelayBatch_commitsAttemptsAndBackoffWithoutRollback() {
        // Arrange: 10 pending events
        for (int i = 0; i < 10; i++) {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setTenantId(tenantId);
            event.setAggregateType("NOTIFICATION");
            event.setAggregateId(UUID.randomUUID());
            event.setEventType("NOTIFICATION_QUEUED");
            event.setTopic("notify.dispatch.email.v1");
            event.setMessageKey("dead-key-" + i);
            event.setPayload("{\"schemaVersion\":1}");
            event.setStatus(OutboxStatus.PENDING);
            event.setAttempts(0);
            event.setCreatedAt(java.time.Instant.now());
            event.setNextAttemptAt(java.time.Instant.now());
            outboxEventRepository.save(event);
        }

        long start = System.currentTimeMillis();
        int published = outboxRelayService.processBatch();
        long elapsed = System.currentTimeMillis() - start;
        log.info("10-row batch against dead broker elapsed: {} ms", elapsed);

        // Proves batch finished within transaction timeout (under 15s) and safe deadline
        assertThat(elapsed)
                .as("10-row batch against dead broker must finish within 15-second transaction timeout")
                .isLessThan(15000L);
        assertThat(published).isEqualTo(0);

        // Crucial: proves transaction COMMITTED and did NOT roll back to attempts=0
        List<OutboxEventEntity> events = outboxEventRepository.findByTenantIdAndStatus(tenantId, OutboxStatus.PENDING);
        List<OutboxEventEntity> batchEvents = events.stream()
                .filter(e -> e.getMessageKey().startsWith("dead-key-"))
                .toList();

        assertThat(batchEvents).hasSize(10);
        assertThat(batchEvents).allMatch(e -> e.getAttempts() == 1);
        assertThat(batchEvents).allMatch(e -> e.getNextAttemptAt() != null && e.getNextAttemptAt().isAfter(java.time.Instant.now()));
    }
}
