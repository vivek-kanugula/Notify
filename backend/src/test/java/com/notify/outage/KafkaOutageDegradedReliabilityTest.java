package com.notify.outage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Channel;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.outbox.service.OutboxRelayService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.DockerClientFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * / -2: PostgreSQL is authoritative, so losing Kafka must cost availability of <b>dispatch</b> and
 * nothing else — acceptance still succeeds, the outbox still holds the truth, and the relay resumes when the
 * broker returns.
 *
 * <p>The outage half (a 10-row batch against a dead broker committing {@code attempts=1} and a backoff
 * without rolling back, inside the transaction deadline) is already proven by
 * {@code KafkaDownStartupAndIngressTest}. What this adds is the half that was missing: <b>recovery</b>.
 *
 * <p><b>Why the isolated database matters here.</b> The outbox relay's fetch is deliberately global
 * ({@code SELECT ... FOR UPDATE SKIP LOCKED} over all PENDING rows, with no tenant predicate), so a relay
 * driven on the shared containers publishes and increments attempts on other suites' outbox rows. An earlier
 * version of this suite did exactly that and broke {@code KafkaDownStartupAndIngressTest}'s
 * {@code attempts == 1} assertion. Running on {@link AbstractIsolatedDatabaseTest} keeps the relay's reach
 * inside this hierarchy, and every assertion below is still scoped to this suite's own message keys rather
 * than to a global published count.
 *
 * <p>The broker is interrupted with {@code docker pause} rather than {@code stop}, because a stopped
 * Testcontainer comes back on a <b>new</b> mapped port while the application's producer is still bound to the
 * old one — the "recovery" would then be indistinguishable from a permanently broken broker. The container is
 * always unpaused in a {@code finally} and again after the class, so no sibling suite inherits a frozen broker.
 */
@TestPropertySource(properties = {
        // The relay is driven explicitly here; no background timer may race the assertions.
        "notify.outbox.relay-enabled=false",
        "notify.reconciler.enabled=false",
        "notify.recovery.enabled=false",
        "notify.retention.enabled=false",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false"
})
class KafkaOutageDegradedReliabilityTest extends AbstractIsolatedDatabaseTest {

    @AfterAll
    static void ensureBrokerRunning() {
        unpauseQuietly();
    }

    private static void pause() {
        DockerClientFactory.lazyClient().pauseContainerCmd(isolatedKafka.getContainerId()).exec();
    }

    private static void unpauseQuietly() {
        try {
            DockerClientFactory.lazyClient().unpauseContainerCmd(isolatedKafka.getContainerId()).exec();
        } catch (RuntimeException ignored) {
            // Already running; nothing is left frozen either way.
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private KafkaDispatchProducer kafkaDispatchProducer;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private OutboxRelayService relay;
    private UUID tenantId;
    private UUID signedUpTenantId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        relay = new OutboxRelayService(outboxEventRepository, kafkaDispatchProducer, clock, transactionManager);
        tenantId = tenantRepository.save(
                new TenantEntity("KafkaOutageOrg", "kafka-outage-" + UUID.randomUUID())).getId();
        signedUpTenantId = null;
    }

    @AfterEach
    void tearDown() {
        // Required by AbstractIsolatedDatabaseTest: deleting the tenant cascades to every row created here.
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        if (signedUpTenantId != null) {
            jdbc.update("DELETE FROM tenants WHERE id = ?", signedUpTenantId);
        }
    }

    private void seedPending(String keyPrefix, int count) {
        for (int i = 0; i < count; i++) {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setTenantId(tenantId);
            event.setAggregateType("NOTIFICATION");
            event.setAggregateId(UUID.randomUUID());
            event.setEventType("NOTIFICATION_QUEUED");
            event.setTopic("notify.dispatch.email.v1");
            event.setMessageKey(keyPrefix + "-" + i);
            event.setPayload("{\"schemaVersion\":1}");
            event.setStatus(OutboxStatus.PENDING);
            event.setAttempts(0);
            event.setCreatedAt(Instant.now());
            event.setNextAttemptAt(Instant.now());
            outboxEventRepository.save(event);
        }
    }

    /** Scoped to this suite's own rows, never a global count. */
    private List<OutboxEventEntity> rowsFor(String keyPrefix, OutboxStatus status) {
        return outboxEventRepository.findByTenantIdAndStatus(tenantId, status).stream()
                .filter(e -> e.getMessageKey().startsWith(keyPrefix))
                .toList();
    }

    @Test
    @DisplayName("acceptance still returns 202 into the outbox while the broker is unreachable")
    void ingressStillAcceptsDuringOutage() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(
                "KafkaOutageTenant-" + suffix, "kafka-outage-" + suffix + "@notify.local", "Password123!");
        String token = objectMapper.readTree(mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();

        pause();
        try {
            CreateNotificationRequest request = new CreateNotificationRequest(
                    Channel.EMAIL, "outage@example.com", "Subject", "Body",
                    null, null, null, null, null, null);

            String body = mockMvc.perform(post("/api/v1/notifications")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();

            UUID notificationId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
            signedUpTenantId = jdbc.queryForObject(
                    "SELECT tenant_id FROM notifications WHERE id = ?", UUID.class, notificationId);

            // Durable acceptance: the row and its outbox event are committed in PostgreSQL regardless of Kafka.
            Long outboxRows = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PENDING'",
                    Long.class, notificationId);
            assertThat(outboxRows)
                    .as("acceptance must leave a PENDING outbox row even with the broker down")
                    .isEqualTo(1L);
        } finally {
            unpauseQuietly();
        }
    }

    @Test
    @DisplayName("the relay backs off while the broker is paused and drains once it returns")
    void relayBacksOffThenResumes() {
        // Control: a healthy broker publishes this suite's rows.
        seedPending("healthy", 3);
        relay.processBatch();
        assertThat(rowsFor("healthy", OutboxStatus.PUBLISHED)).hasSize(3);

        seedPending("outage", 3);
        pause();
        try {
            long start = System.currentTimeMillis();
            relay.processBatch();
            long elapsed = System.currentTimeMillis() - start;

            assertThat(elapsed)
                    .as("the batch must finish inside the 15 s transaction timeout, not hang")
                    .isLessThan(15_000L);
            assertThat(rowsFor("outage", OutboxStatus.PUBLISHED))
                    .as("nothing of ours can be published while the broker is frozen")
                    .isEmpty();

            List<OutboxEventEntity> pending = rowsFor("outage", OutboxStatus.PENDING);
            assertThat(pending).hasSize(3);
            assertThat(pending).allMatch(e -> e.getAttempts() == 1,
                    "the failed attempt is committed, so the transaction did not roll back");
            assertThat(pending).allMatch(e -> e.getNextAttemptAt() != null
                    && e.getNextAttemptAt().isAfter(Instant.now()), "a backoff is scheduled");
        } finally {
            unpauseQuietly();
        }

        // Recovery. The backoff is already asserted above; clearing it for this tenant keeps the test bounded
        // instead of sleeping out a real backoff window.
        for (int attempt = 0; attempt < 10 && rowsFor("outage", OutboxStatus.PUBLISHED).size() < 3; attempt++) {
            jdbc.update("UPDATE outbox_events SET next_attempt_at = now() "
                    + "WHERE tenant_id = ? AND status = 'PENDING'", tenantId);
            relay.processBatch();
        }

        assertThat(rowsFor("outage", OutboxStatus.PUBLISHED))
                .as("once the broker is back the relay must drain the backlog it could not publish")
                .hasSize(3);
        assertThat(rowsFor("outage", OutboxStatus.PENDING)).isEmpty();
    }
}
