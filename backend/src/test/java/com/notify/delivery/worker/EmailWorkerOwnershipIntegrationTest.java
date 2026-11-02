package com.notify.delivery.worker;

import com.notify.AbstractKafkaIntegrationTest;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.repository.DeliveryAttemptRepository;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O3 (stale owner), O5 (ambiguous commit: committed / superseded / absent / unresolved), K10 (unresolved PROCESSING),
 * R3 (refund + reclaim at the same generation) with the real worker against isolated Testcontainers services.
 */
@Import({ScriptedProviderConfig.class, EmailWorkerOwnershipIntegrationTest.FaultInjection.class})
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.concurrency.email=1",
        "notify.worker.email.retry-interval=100ms",
        "notify.worker.email.pause-initial=300ms",
        "notify.worker.email.pause-max=1s",
        "notify.retry.base-delay=2s",
        "notify.retry.max-delay=10s"
})
class EmailWorkerOwnershipIntegrationTest extends AbstractKafkaIntegrationTest {

    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.email.own");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    private static final Duration WAIT = Duration.ofSeconds(60);

    static {
        try {
            WorkerKafkaSupport.createTopics(kafka.getBootstrapServers(), 3, TOPIC, DLT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("notify.worker.email.topic", () -> TOPIC);
        registry.add("notify.worker.email.group-id", () -> GROUP);
    }

    /**
     * Result service that can emulate a lost commit acknowledgment: the real transaction commits, then the caller sees
     * a failure (ambiguous commit), or it fails before committing anything. Runs outside any caller transaction.
     */
    static class FaultyResultService extends DeliveryResultService {
        private final DeliveryResultService real;
        private final AtomicInteger commitThenFail = new AtomicInteger();
        private final AtomicInteger failBeforeCommit = new AtomicInteger();

        // Accessed through methods: the bean is a CGLIB proxy, so direct field access would hit the proxy instance.
        public void armCommitThenFail(int times) {
            commitThenFail.set(times);
        }

        public void armFailBeforeCommit(int times) {
            failBeforeCommit.set(times);
        }

        public int remainingCommitThenFail() {
            return commitThenFail.get();
        }

        FaultyResultService(NotificationTransitioner t, NotificationService n, DeliveryAttemptRepository r, Clock c, DeliveryResultService real) {
            // / added the per-channel side-effect list; this email-only fake registers none, which is
            // exactly how EMAIL behaves in production.
            super(t, n, r, new com.notify.observability.metrics.NotifyMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    new com.notify.observability.config.MetricsProperties(false)), c, java.util.List.of());
            this.real = real;
        }

        @Override
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void persist(ClaimSnapshot claim, PersistableOutcome outcome) {
            if (failBeforeCommit.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new QueryTimeoutException("injected failure before commit");
            }
            real.persist(claim, outcome);
            if (commitThenFail.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new QueryTimeoutException("injected: commit acknowledgement lost");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjection {
        @Bean
        @Primary
        FaultyResultService faultyResultService(NotificationTransitioner t, NotificationService n, DeliveryAttemptRepository r,
                                                Clock c, @Qualifier("deliveryResultService") DeliveryResultService real) {
            return new FaultyResultService(t, n, r, c, real);
        }
    }

    @Autowired private ScriptedEmailProvider provider;
    @Autowired private FaultyResultService faulty;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        faulty.armCommitThenFail(0);
        faulty.armFailBeforeCommit(0);
        tenantId = tenantRepository.save(new TenantEntity("OwnerOrg", "owner-org-" + UUID.randomUUID())).getId();
    }

    private String bootstrap() {
        return kafka.getBootstrapServers();
    }

    private UUID seed(String status, int generation, int attemptCount, int claimSeq) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body, dispatch_generation,
                    delivery_cycle, attempt_count, claim_seq, max_attempts, sender, queued_at)
                VALUES (?, ?, 'EMAIL', ?, 'rcpt@owner.notify.local', 'subject', 'body', ?, 1, ?, ?, 5, 'from@owner.notify.local', now())
                """, id, tenantId, status, generation, attemptCount, claimSeq);
        return id;
    }

    private void publish(UUID id, int generation) throws Exception {
        WorkerKafkaSupport.publish(bootstrap(), TOPIC, id.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, id, generation, "NOTIFICATION_QUEUED"));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, attempt_count, claim_seq, deferral_count FROM notifications WHERE id = ?", id);
    }

    private void awaitCommitted() {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(WorkerKafkaSupport.uncommitted(bootstrap(), GROUP, TOPIC)).isZero());
    }

    /** Dead-letter records for one notification (the DLT topic is shared by every test in this class). */
    private List<ConsumerRecord<String, byte[]>> deadLetteredFor(UUID id) {
        return WorkerKafkaSupport.readAll(bootstrap(), DLT).stream().filter(r -> id.toString().equals(r.key())).toList();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ─── O3 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O3 a stale owner whose claim was superseded while its send was in flight writes nothing; the new owner's row is untouched")
    void staleOwnerIsFencedOut() throws Exception {
        UUID id = seed("QUEUED", 1, 0, 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.script(r -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DeliveryResult.Success("stale_owner_success");
        });

        publish(id, 1);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(row(id)).containsEntry("status", "PROCESSING");
        // another owner (reaper / second worker) takes over: the claim identity moves on while owner A is still sending
        jdbc.update("UPDATE notifications SET claim_seq = claim_seq + 1 WHERE id = ?", id);
        Map<String, Object> ownerB = row(id);
        int historyBefore = count("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", id);

        release.countDown();
        awaitCommitted();   // owner A's record is acknowledged: its result was discarded, not retried

        assertThat(row(id)).as("owner B's row untouched by the late result").isEqualTo(ownerB);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", id)).isEqualTo(historyBefore);
        assertThat(provider.callsFor(id)).isEqualTo(1);
        assertThat(deadLetteredFor(id)).as("nothing is dead-lettered for this notification").isEmpty();
    }

    @Test
    @DisplayName("O5 the row disappears while the send is in flight: the result is discarded (ABSENT) and the record acknowledged")
    void rowDeletedWhileInFlight() throws Exception {
        UUID id = seed("QUEUED", 1, 0, 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.script(r -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DeliveryResult.Success("orphan_success");
        });

        publish(id, 1);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM notifications WHERE id = ?", id);
        release.countDown();

        awaitCommitted();
        assertThat(count("SELECT count(*) FROM notifications WHERE id = ?", id)).isZero();
        assertThat(deadLetteredFor(id)).as("nothing is dead-lettered for this notification").isEmpty();
    }

    // ─── O5 ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O5 the result commits but the acknowledgment is lost: the retry is fenced out, reconciliation sees COMMITTED, one send, one attempt, one DELIVERED history row")
    void ambiguousCommitIsReconciled() throws Exception {
        UUID id = seed("QUEUED", 1, 0, 0);
        faulty.armCommitThenFail(1);

        publish(id, 1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(row(id)).containsEntry("status", "DELIVERED"));
        awaitCommitted();

        assertThat(provider.callsFor(id)).as("the provider is never called again for the same claim").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_status_history WHERE notification_id = ? AND to_status = 'DELIVERED'", id)).isEqualTo(1);
        assertThat(faulty.remainingCommitThenFail()).isZero();
        assertThat(deadLetteredFor(id)).as("nothing is dead-lettered for this notification").isEmpty();
    }

    @Test
    @DisplayName("O5/K10 persistence fails for good: the final read shows PROCESSING (UNRESOLVED), bounded retries, then quarantine to the DLT; offset committed only after the DLT send; still one provider call")
    void persistFailsThenQuarantine() throws Exception {
        UUID id = seed("QUEUED", 1, 0, 0);
        faulty.armFailBeforeCommit(10_000);

        publish(id, 1);
        awaitCommitted();   // commit happens only after the dead-letter send succeeded

        List<ConsumerRecord<String, byte[]>> dead = WorkerKafkaSupport.readAll(bootstrap(), DLT).stream()
                .filter(r -> id.toString().equals(r.key())).toList();
        assertThat(dead).hasSize(1);
        assertThat(new String(dead.get(0).headers().lastHeader("x-notify-dlt-reason").value(), StandardCharsets.UTF_8))
                .isEqualTo("UNRESOLVED_PROCESSING_STUCK");
        assertThat(provider.callsFor(id)).as("quarantined without a duplicate provider call").isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "PROCESSING");   // orphaned until /(decision 1)
        faulty.armFailBeforeCommit(0);
    }

    // ─── K10 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("K10 a record meeting a PROCESSING row at its generation is retried, then dead-lettered (UNRESOLVED_PROCESSING_STUCK); no provider call; row untouched")
    void unresolvedProcessingIsQuarantined() throws Exception {
        UUID id = seed("PROCESSING", 1, 1, 3);
        Map<String, Object> before = row(id);

        publish(id, 1);
        awaitCommitted();

        List<ConsumerRecord<String, byte[]>> dead = WorkerKafkaSupport.readAll(bootstrap(), DLT).stream()
                .filter(r -> id.toString().equals(r.key())).toList();
        assertThat(dead).hasSize(1);
        assertThat(new String(dead.get(0).headers().lastHeader("x-notify-dlt-reason").value(), StandardCharsets.UTF_8))
                .isEqualTo("UNRESOLVED_PROCESSING_STUCK");
        assertThat(new String(dead.get(0).value(), StandardCharsets.UTF_8)).contains(id.toString());
        assertThat(provider.callsFor(id)).isZero();
        assertThat(row(id)).isEqualTo(before);
    }

    // ─── R3 / O3 (refund + reclaim at the same generation) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("R3/O3 a deferral refunds the attempt; the same-generation reclaim gets the same attempt number with a higher claim_seq and delivers")
    void deferralThenSameGenerationReclaim() throws Exception {
        UUID id = seed("QUEUED", 1, 0, 0);
        AtomicInteger sends = new AtomicInteger();
        provider.script(r -> sends.incrementAndGet() == 1
                ? new DeliveryResult.Deferred("FAKE_DEFERRED", Duration.ofSeconds(1))
                : new DeliveryResult.Success("second_owner"));

        publish(id, 1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(row(id)).containsEntry("status", "RETRYING"));
        assertThat(((Number) row(id).get("attempt_count")).intValue()).isZero();
        assertThat(((Number) row(id).get("claim_seq")).intValue()).isEqualTo(1);
        awaitCommitted();
        Thread.sleep(1_500);   // the deferral is due

        publish(id, 1);        // the same dispatch generation is redelivered (due retry claimed directly)
        await().atMost(WAIT).untilAsserted(() -> assertThat(row(id)).containsEntry("status", "DELIVERED"));
        awaitCommitted();

        Map<String, Object> done = row(id);
        assertThat(((Number) done.get("claim_seq")).intValue()).isEqualTo(2);
        assertThat(((Number) done.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number) done.get("deferral_count")).intValue()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isEqualTo(1);
        assertThat(provider.callsFor(id)).isEqualTo(2);
    }
}
