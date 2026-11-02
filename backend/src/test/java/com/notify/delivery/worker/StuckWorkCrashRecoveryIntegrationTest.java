package com.notify.delivery.worker;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.recovery.StuckProcessingReaper;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.model.NotificationTransitioner.ReapOutcome;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationReconcilerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Crash, restart and ambiguity (/ Slice 7, ; , , , ).
 *
 * <p>Isolated Testcontainers PostgreSQL and Kafka, a unique topic and consumer group, the real email worker and a
 * scripted provider. No live send, no development service.
 *
 * <p><b>How a crash is modelled.</b> A worker crash is not a thrown exception - it is a claim that commits and a
 * result that never does. So each case claims the notification through the real {@code NotificationTransitioner},
 * optionally calls the provider through the real {@code DeliveryOrchestrator}, and then simply <em>stops</em>,
 * committing no result. Back-dating {@code processing_started_at} is what makes the abandoned claim visible to the
 * reaper; nothing else about it is simulated.
 *
 * <p><b>What the two cases are for.</b> They are the same code path and deliberately different outcomes: a crash
 * <em>before</em> the provider was reached costs nothing externally, while a crash <em>after</em> it produces a
 * second external effect. The second case is not a defect to be fixed but the accepted limit of recovery under
 * ambiguous acceptance - and the assertion that matters is that the duplicate carries the <b>same</b>
 * {@code <notificationId>-<deliveryCycle>} identity, which is what lets a provider collapse it.
 */
@Import(ScriptedProviderConfig.class)
@TestPropertySource(properties = {
        "notify.worker.email.enabled=true",
        "notify.worker.concurrency.email=1",
        "notify.worker.email.retry-interval=100ms",
        "notify.worker.email.health-poll-interval=200ms",
        "notify.recovery.enabled=true",
        "notify.recovery.stuck-after=1m",
        "notify.recovery.scan-interval=10s",
        "notify.recovery.batch-size=20",
        "notify.retry.base-delay=1s",
        "notify.retry.max-delay=5s",
        "notify.mail.from=noreply@recovery.notify.local",
        // is "recovery proceeds through Quartz and the reconciler with no manual step", so the reconciler
        // has to be the thing that releases the reaped row. The test profile disables it by default; it is enabled
        // here and driven explicitly, rather than waiting on its own schedule.
        "notify.reconciler.enabled=true",
        // The interval is a day, not a minute, on purpose. Spring caches this context, so its @Scheduled loop keeps
        // running after this class finishes: a reconciler firing again mid-suite would release another test's
        // RETRYING row and bump its generation out from under it. It fires once at startup, inside this class, and
        // every reconciliation this test depends on is driven explicitly below.
        "notify.reconciler.interval-ms=86400000"
})
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class StuckWorkCrashRecoveryIntegrationTest extends AbstractIsolatedDatabaseTest {

    static final String TOPIC = WorkerKafkaSupport.uniqueTopic("notify.test.email.recovery");
    static final String DLT = TOPIC + ".DLT";
    static final String GROUP = "grp-" + TOPIC;
    private static final Duration WAIT = Duration.ofSeconds(60);

    static {
        try {
            WorkerKafkaSupport.createTopics(isolatedKafka.getBootstrapServers(), 3, TOPIC, DLT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void workerProperties(DynamicPropertyRegistry registry) {
        registry.add("notify.worker.email.topic", () -> TOPIC);
        registry.add("notify.worker.email.group-id", () -> GROUP);
    }

    @Autowired private ScriptedEmailProvider provider;
    @Autowired private StuckProcessingReaper reaper;
    @Autowired private NotificationTransitioner transitioner;
    @Autowired private NotificationService notificationService;
    @Autowired private NotificationReconcilerService reconciler;
    @Autowired private DeliveryOrchestrator orchestrator;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        tenantId = tenantRepository.save(
                new TenantEntity("RecoveryOrg", "recovery-org-" + UUID.randomUUID())).getId();
    }

    /** Fixture-owned cleanup: deleting the tenant cascades to everything this fixture created, and nothing else. */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedQueued(int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', 'rcpt@recovery.notify.local', 'subject', 'body',
                    0, 1, 0, ?, now())
                """, id, tenantId, maxAttempts);
        return id;
    }

    /** Claims as a worker would, then abandons the claim without committing any result: a crash. */
    private ClaimSnapshot claimThenCrash(UUID id) {
        ClaimResult result = transitioner.claim(tenantId, id, 0, null);
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        jdbc.update("UPDATE notifications SET processing_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), id);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private int generationOf(UUID id) {
        return jdbc.queryForObject("SELECT dispatch_generation FROM notifications WHERE id = ?", Integer.class, id);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * The hop the outbox relay performs in production, done here explicitly because the relay publishes to the real
     * topic name while this test owns a unique one. The relay itself is covered by /; what is under test here
     * is that recovery produces the QUEUED row and the outbox event that the relay would carry.
     */
    private void deliverTheOutboxEvent(UUID id) throws Exception {
        WorkerKafkaSupport.publish(isolatedKafka.getBootstrapServers(), TOPIC, id.toString(),
                WorkerKafkaSupport.dispatchEvent(tenantId, id, generationOf(id), "NOTIFICATION_QUEUED"));
    }

    /** The real restart chain: reconcile the overdue RETRYING row, which releases it to QUEUED with a new generation. */
    private void recoverThroughTheRetryPath(UUID id) throws Exception {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            reconciler.reconcileOverdueRetrying();
            assertThat(status(id)).isEqualTo("QUEUED");
        });
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", id))
                .as("recovery produced the durable dispatch event the relay carries")
                .isPositive();
        deliverTheOutboxEvent(id);
    }

    @Test
    @DisplayName("the suite really is running against its own database, not the shared one")
    void theIsolatedDatabaseIsInEffect() {
        // Kept because the first two attempts at isolation were silently ineffective: this suite owns its PostgreSQL
        // container, so at the start of a test nothing else can have written a notification.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class))
                .as("a private database plus fixture-owned cleanup means the table starts empty")
                .isZero();
    }

    @Test
    @DisplayName("a crash before the provider was reached: recovered and delivered exactly once")
    void aCrashBeforeProviderContactDeliversExactlyOnce() throws Exception {
        UUID id = seedQueued(5);
        claimThenCrash(id);
        assertThat(provider.calls()).as("the crash happened before any provider contact").isZero();
        assertThat(status(id)).isEqualTo("PROCESSING");

        // Recovery, with no manual step: the scan reaps, and the ordinary retry path carries it back to QUEUED.
        assertThat(reaper.scan().rescheduled()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("RETRYING");
        recoverThroughTheRetryPath(id);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("DELIVERED"));

        assertThat(provider.calls()).as("exactly one external effect").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("one attempt row, for the attempt that actually committed")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                id, "NOTIFICATION_DEAD_LETTERED"))
                .as("nothing was dead-lettered").isZero();
    }

    @Test
    @DisplayName("a crash after provider acceptance: a second effect is possible, with the same identity")
    void aCrashAfterAcceptanceCanDuplicateUnderOneIdentity() throws Exception {
        UUID id = seedQueued(5);
        ClaimSnapshot crashed = claimThenCrash(id);

        // The provider accepts, and then the worker dies before its result can commit - the ambiguous case.
        orchestrator.deliver(crashed);
        assertThat(provider.calls()).isEqualTo(1);
        assertThat(status(id)).as("no result committed, so the row is still PROCESSING").isEqualTo("PROCESSING");
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("and no attempt row exists for a result that never committed")
                .isZero();

        assertThat(reaper.scan().rescheduled()).isEqualTo(1);
        recoverThroughTheRetryPath(id);

        await().atMost(WAIT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("DELIVERED"));

        // The accepted limit, asserted rather than hidden: the recipient may receive this twice.
        assertThat(provider.calls()).as("two external effects: recovery is not exactly-once").isEqualTo(2);
        List<EmailSendRequest> sent = provider.sent();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).idempotencyKey())
                .as("the duplicate carries the IDENTICAL <notificationId>-<deliveryCycle> identity, which is what "
                        + "lets a provider that deduplicates collapse it; a reap does not start a new cycle")
                .isEqualTo(sent.get(0).idempotencyKey());
        assertThat(sent.get(0).idempotencyKey()).isEqualTo(id + "-1");
        // Locally there is still exactly one of everything that matters.
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("one attempt row: only the committed attempt is recorded")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE id = ? AND status = 'DELIVERED'", id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("recovery needs no manual step, and it leaves another channel's work alone")
    void recoveryIsAutomaticAndChannelIndependent() {
        UUID email = seedQueued(5);
        claimThenCrash(email);

        // An IN_APP notification claimed and abandoned in the same window. The in-app worker is disabled here, so
        // nothing will redeliver it; what matters is that the EMAIL recovery does not touch its own progress.
        UUID inApp = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'IN_APP', 'QUEUED', 'inbox-user', 'subject', 'body', 0, 1, 0, 1, now())
                """, inApp, tenantId);
        claimThenCrash(inApp);

        StuckProcessingReaper.ScanReport report = reaper.scan();

        // Exact, because this suite owns its database.
        assertThat(report.examined()).isEqualTo(2);
        assertThat(report.rescheduled()).as("the EMAIL claim had budget left").isEqualTo(1);
        assertThat(report.exhausted()).as("the IN_APP claim's single attempt was spent").isEqualTo(1);
        assertThat(status(email)).isEqualTo("RETRYING");
        assertThat(status(inApp)).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                inApp, "NOTIFICATION_DEAD_LETTERED"))
                .as("the exhausted claim is dead-lettered, in its own right")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inapp_messages WHERE notification_id = ?", inApp))
                .as("holds: an unrecoverable IN_APP delivery leaves no inbox row")
                .isZero();
        assertThat(provider.calls()).as("recovery itself calls no provider").isZero();
    }
}
