package com.notify.delivery.recovery;

import com.notify.AbstractIsolatedDatabaseTest;
import com.notify.common.model.Channel;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reaper's scan with recovery <b>explicitly enabled</b> (/ , decision ; , ,
 * ).
 *
 * <p>Recovery is off by default, so the only way to exercise the scan is to opt in - which is what the acceptance
 * run does too. The timing set is scaled down to its validated floor rather than shortened arbitrarily:
 * {@code ReaperProperties} refuses a cutoff below one minute, so the fixture back-dates its claims instead of
 * weakening the bound.
 */
@TestPropertySource(properties = {
        "notify.recovery.enabled=true",
        "notify.recovery.stuck-after=1m",
        "notify.recovery.scan-interval=10s",
        "notify.recovery.batch-size=2",
        "notify.recovery.max-pages-per-scan=2"
})
// / -3: this class enables background work (relay, reconciler, reaper or worker). Spring caches
// test contexts for the whole JVM run, so without this the schedulers keep polling after the class finishes -
// and the outbox relay's fetch is global, with no tenant predicate. Dirtying the context shuts those pools
// down at class exit instead of leaking background polling into later suites.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReaperEnabledScanIntegrationTest extends AbstractIsolatedDatabaseTest {


    @Autowired private StuckProcessingReaper reaper;
    @Autowired private ReaperProperties props;
    @Autowired private NotificationTransitioner transitioner;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("ScanOrg", "scan-org-" + UUID.randomUUID())).getId();
    }

    /**
     * Fixture-owned cleanup: removes exactly what this fixture created and nothing else. Deleting the tenant
     * cascades to its notifications, history, attempts, inbox rows and outbox events, so the next test starts from
     * an empty table without any test needing to know what the others did.
     */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    /** A claimed notification whose claim is then back-dated past the cutoff. */
    private UUID stuck(int maxAttempts, String recipient) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', ?, 'Subject', 'Body', 0, 1, 0, ?, now())
                """, id, tenantId, recipient, maxAttempts);
        assertThat(transitioner.claim(tenantId, id, 0, null)).isInstanceOf(ClaimResult.Claimed.class);
        jdbc.update("UPDATE notifications SET processing_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), id);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("the suite really is running against its own database, not the shared one")
    void theIsolatedDatabaseIsInEffect() {
        // The isolation guard, kept because the first two attempts at it were silently ineffective: this suite owns
        // its PostgreSQL container, so at the start of a test nothing else can have written a notification.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class))
                .as("a private database plus fixture-owned cleanup means the table starts empty")
                .isZero();
    }

    @Test
    @DisplayName("the opted-in configuration is the one the scan uses")
    void theOptedInConfigurationIsBound() {
        assertThat(props.enabled()).isTrue();
        assertThat(props.stuckAfter()).isEqualTo(Duration.ofMinutes(1));
        assertThat(props.batchSize()).isEqualTo(2);
        assertThat(props.maxRowsPerScan()).as("one scan is bounded at batch x pages").isEqualTo(4);
    }

    @Test
    @DisplayName("an enabled scan recovers every stuck claim it examines, by the right path for each")
    void anEnabledScanRecoversStuckClaims() {
        UUID recoverable = stuck(5, "recoverable@example.com");
        UUID spent = stuck(1, "spent@example.com");          // the claim consumed its only attempt

        StuckProcessingReaper.ScanReport report = reaper.scan();

        // Exact, because this suite owns its database: nothing else can contribute a row to the scan.
        assertThat(report.examined()).isEqualTo(2);
        assertThat(report.rescheduled()).isEqualTo(1);
        assertThat(report.exhausted()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(statusOf(recoverable)).isEqualTo("RETRYING");
        assertThat(statusOf(spent)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, spent))
                .as("one durable dead-letter event for the exhausted claim only").isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, recoverable))
                .isEqualTo(0L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM delivery_attempts WHERE notification_id IN (?, ?)", Long.class,
                recoverable, spent))
                .as("neither path invents an attempt row").isEqualTo(0L);
    }

    @Test
    @DisplayName("the scan is bounded: more stuck rows than its budget are left for the next run")
    void theScanIsBounded() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {                         // budget is 2 x 2 = 4
            ids.add(stuck(5, "bounded-" + i + "@example.com"));
        }

        StuckProcessingReaper.ScanReport first = reaper.scan();

        assertThat(first.examined())
                .as("never more than batch-size x max-pages-per-scan in one scan")
                .isLessThanOrEqualTo(props.maxRowsPerScan());
        assertThat(first.reaped()).isEqualTo(first.examined());

        long stillStuck = ids.stream().filter(id -> statusOf(id).equals("PROCESSING")).count();
        assertThat(stillStuck).as("the remainder is left for the next scan, not dropped").isPositive();

        // The next scans pick up where this one stopped, so nothing is stranded.
        for (int scan = 0; scan < 6 && ids.stream().anyMatch(id -> statusOf(id).equals("PROCESSING")); scan++) {
            reaper.scan();
        }
        assertThat(ids.stream().filter(id -> statusOf(id).equals("PROCESSING")).count())
                .as("every row this test created was eventually recovered").isZero();
    }

    @Test
    @DisplayName("a claim younger than the cutoff survives an enabled scan untouched")
    void aYoungClaimSurvivesTheScan() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', 'young@example.com', 'Subject', 'Body', 0, 1, 0, 5, now())
                """, id, tenantId);
        transitioner.claim(tenantId, id, 0, null);            // processing_started_at is now, not back-dated

        StuckProcessingReaper.ScanReport report = reaper.scan();

        assertThat(report.examined()).isZero();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
    }
}
