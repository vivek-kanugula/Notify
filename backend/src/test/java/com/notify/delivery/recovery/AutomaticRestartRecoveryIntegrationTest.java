package com.notify.delivery.recovery;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.NotifyBackendApplication;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Automatic restart recovery, with nothing driven by hand (/ , ).
 *
 * <p><b>Why this test exists separately from the others.</b> The recovery suites alongside it call
 * {@code reaper.scan()}, invoke the reconciler and publish the dispatch event themselves. That is useful
 * <em>component</em> coverage - it pins each predicate and each transition precisely - but it cannot show that a
 * restarted application recovers on its own, because the test is doing the recovering. This journey removes every
 * one of those hands:
 * <ul>
 *   <li>durable claimed work is left behind with no committed result, by a real claim through the real
 *       transitioner;</li>
 *   <li>a <b>second application context is started</b> against the same retained PostgreSQL and Kafka - an actual
 *       restart, not a simulated one;</li>
 *   <li>that instance runs the real scheduled paths: the stuck-work scan, the reconciler, the outbox relay and the
 *       email worker, all on their own timers;</li>
 *   <li>the test then only <b>waits and observes</b>. It never calls scan, reconcile, release or publish.</li>
 * </ul>
 *
 * <p><b>What is scaled, and what is not.</b> Only the scan and poll intervals are shortened, and the claim is
 * back-dated so it is already past the cutoff - both are fixture timing, not production behaviour. The cutoff
 * itself still goes through {@code ReaperProperties}' one-minute floor, so the validated relationship that stops a
 * merely slow worker from being reaped is exercised rather than bypassed. The provider is the production-wired
 * fake; no external call is made.
 */
@TestPropertySource(properties = {
        // This context is the instance that CRASHES: it claims work and never completes it, so it runs no worker,
        // no recovery, no reconciler and no relay. Everything that recovers runs in the restarted context below.
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.recovery.enabled=false",
        "notify.reconciler.enabled=false",
        "notify.outbox.relay-enabled=false",
        "notify.mail.from=noreply@restart.notify.local"
})
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AutomaticRestartRecoveryIntegrationTest extends AbstractIsolatedDatabaseTest {

    private static final Duration WAIT = Duration.ofSeconds(120);

    @Autowired private NotificationTransitioner transitioner;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private ConfigurableApplicationContext restarted;

    @BeforeEach
    void setUp() {
        // The guard the sibling suites on this base already carry, and the one this suite needs most: it starts a
        // second context with recovery AND the email worker enabled, so a stray PROCESSING row left by a sibling
        // would be reaped and delivered here - invisibly, because every assertion below is id-scoped. Checked
        // before the tenant is created and before any recovery context exists; rows are never deleted by age.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class))
                .as("a private database plus fixture-owned cleanup means the table starts empty")
                .isZero();
        tenantId = tenantRepository.save(
                new TenantEntity("RestartOrg", "restart-org-" + UUID.randomUUID())).getId();
    }

    @AfterEach
    void tearDown() {
        if (restarted != null) {
            restarted.close();
            restarted = null;
        }
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    /** A notification claimed for real and then abandoned: the durable state a crashed worker leaves behind. */
    private UUID claimedAndAbandoned(int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', 'rcpt@restart.notify.local', 'subject', 'body',
                    0, 1, 0, ?, now())
                """, id, tenantId, maxAttempts);
        assertThat(transitioner.claim(tenantId, id, 0, null)).isInstanceOf(ClaimResult.Claimed.class);
        // Fixture timing only: the claim is dated before the cutoff so the restarted instance sees it as abandoned.
        jdbc.update("UPDATE notifications SET processing_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), id);
        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("no result was committed, which is what a crash leaves behind")
                .isZero();
        return id;
    }

    /**
     * Starts a second application instance against the retained containers, with every scheduled recovery path on.
     * This is the restart under test; the test calls no recovery method itself.
     */
    private void restartWithRecoveryEnabled() {
        String[] overrides = {
                "--spring.datasource.url=" + isolatedPostgres.getJdbcUrl(),
                "--spring.datasource.username=" + isolatedPostgres.getUsername(),
                "--spring.datasource.password=" + isolatedPostgres.getPassword(),
                "--spring.kafka.bootstrap-servers=" + isolatedKafka.getBootstrapServers(),
                // This database was already migrated by the first context.
                "--spring.flyway.enabled=false",
                // The real scheduled recovery chain, at test cadence. The cutoff still passes ReaperProperties'
                // one-minute floor, so the validated relationship is exercised rather than bypassed.
                "--notify.recovery.enabled=true",
                "--notify.recovery.stuck-after=1m",
                "--notify.recovery.scan-interval=10s",
                "--notify.reconciler.enabled=true",
                "--notify.reconciler.interval-ms=2000",
                "--notify.outbox.relay-enabled=true",
                "--notify.outbox.relay-interval-ms=500",
                // The worker that delivers the recovered notification, on the contracted default topic so the
                // relay's own published event reaches it. The provider is the production-wired fake.
                "--notify.worker.email.enabled=true",
                "--notify.worker.concurrency.email=1",
                "--notify.email.provider=fake",
                "--notify.mail.from=noreply@restart.notify.local",
                "--notify.retry.base-delay=1s",
                "--notify.retry.max-delay=5s",
                "--logging.level.com.notify.delivery.recovery=DEBUG"
        };

        restarted = new SpringApplicationBuilder(NotifyBackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .listeners(refuseAnyDatabaseButTheIsolatedOne())
                .run(overrides);
    }

    /**
     * Refuses to create the restarted context unless the resolved datasource is this suite's own container.
     *
     * <p>Not defensive decoration. An earlier version of this test passed its overrides through
     * {@code SpringApplicationBuilder.properties(...)}, which contributes {@code defaultProperties} - the
     * <b>lowest</b>-precedence source - so {@code application.yml} won and the instance connected to the real
     * development database instead. Hence two changes: the overrides are command-line arguments now, and this
     * listener fires on {@code ApplicationEnvironmentPreparedEvent}, before the context exists, so a wrong URL
     * stops the restart before any bean, any Flyway run or any scheduled task can touch the wrong database.
     */
    private ApplicationListener<ApplicationEnvironmentPreparedEvent> refuseAnyDatabaseButTheIsolatedOne() {
        return event -> {
            String url = event.getEnvironment().getProperty("spring.datasource.url");
            if (!isolatedPostgres.getJdbcUrl().equals(url)) {
                throw new IllegalStateException("Refusing to start the restarted instance: it resolved " + url
                        + " instead of this suite's isolated container " + isolatedPostgres.getJdbcUrl());
            }
        };
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("a restarted instance recovers abandoned work and delivers it, with nothing driven by hand")
    void aRestartRecoversAbandonedWorkByItself() {
        UUID id = claimedAndAbandoned(5);

        restartWithRecoveryEnabled();

        // From here the test only observes. No scan, no reconcile, no release, no publish.
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("DELIVERED"));

        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("one attempt row, for the attempt that actually committed")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT provider FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("fake");
        assertThat(count("""
                SELECT count(*) FROM notification_status_history
                WHERE notification_id = ? AND to_status = 'RETRYING' AND reason LIKE 'Recovered%'
                """, id))
                .as("the recovery really was the reaper's, not a coincidence")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                id, "NOTIFICATION_DEAD_LETTERED"))
                .as("nothing was dead-lettered: the budget had room")
                .isZero();
    }

    @Test
    @DisplayName("a restart terminates abandoned work whose budget is spent, without sending again")
    void aRestartTerminatesExhaustedWorkByItself() {
        UUID id = claimedAndAbandoned(1);        // the claim consumed the only attempt

        restartWithRecoveryEnabled();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("FAILED"));

        assertThat(jdbc.queryForObject("SELECT last_error_code FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo(StuckProcessingReaper.WORKER_LOST);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                id, "NOTIFICATION_DEAD_LETTERED"))
                .as("one durable dead-letter event")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("and no attempt row: the outcome was never observed")
                .isZero();
        // It stays terminal: a later scan must not resurrect it.
        assertThat(status(id)).isEqualTo("FAILED");
    }
}
