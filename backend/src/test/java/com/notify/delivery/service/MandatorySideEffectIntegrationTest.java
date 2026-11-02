package com.notify.delivery.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.repository.DeliveryAttemptRepository;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mandatory in-app persistence hook (condition C1), with <b>no</b> {@code DeliveryResultSideEffect}
 * registered.
 *
 * <p><b>What was wrong, and why the existing tests did not catch it.</b> {@code DeliveryResultService} looked the
 * side effect up and invoked it <em>only when non-null</em>, so a deployment in which the IN_APP hook was absent -
 * not registered, excluded by a condition, or filtered out of the injected list - committed DELIVERED and a SUCCESS
 * attempt with no inbox row at all. {@code InAppPersistenceIntegrationTest.missingRecipientRollsBack} exercises a
 * writer that is <em>present and invoked</em> and throws inside; it says nothing about an absent hook, and the full
 * application context always has one. This test therefore builds the service by hand with an empty side-effect list,
 * which is the only way to reach the branch.
 *
 * <p>It is PostgreSQL-backed because the claim under test is transactional: the fenced status update, its history
 * row and the attempt row must all be gone after the throw.
 */
class MandatorySideEffectIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private NotificationTransitioner transitioner;
    @Autowired private NotificationService notificationService;
    @Autowired private DeliveryAttemptRepository attemptRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private DeliveryResultService productionService;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private UUID tenantId;

    /** A throwaway metrics bean for direct construction; / added it to the service constructor. */
    private static com.notify.observability.metrics.NotifyMetrics newMetrics() {
        return new com.notify.observability.metrics.NotifyMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new com.notify.observability.config.MetricsProperties(false));
    }

    /** The service exactly as a deployment with no registered side effect would wire it. */
    private DeliveryResultService withNoSideEffects() {
        return new DeliveryResultService(transitioner, notificationService, attemptRepository, newMetrics(), clock, List.of());
    }

    /**
     * Runs {@code persist} inside a transaction, the way the proxied bean does.
     *
     * <p>A hand-constructed instance is not a Spring proxy, so {@code @Transactional} on {@code persist} does not
     * apply to it and each write would commit on its own - which would hide the very rollback under test. The
     * template supplies the boundary the production call always has; {@code persist} joins it with the default
     * REQUIRED propagation, exactly as it joins the worker's.
     */
    private void persistInTransaction(DeliveryResultService service, ClaimSnapshot claim,
                                      PersistableOutcome.Delivered outcome) {
        transactions.executeWithoutResult(status -> service.persist(claim, outcome));
    }

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("HookOrg", "hook-org-" + UUID.randomUUID())).getId();
    }

    private UUID seedQueued(Channel channel, String recipient) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, ?, 'QUEUED', ?, 'Subject', 'Body', 0, 1, 0, 5, now())
                """, id, tenantId, channel.name(), recipient);
        return id;
    }

    private ClaimSnapshot claim(UUID notificationId) {
        ClaimResult result = transitioner.claim(tenantId, notificationId, 0, null);
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    private PersistableOutcome.Delivered delivered(ClaimSnapshot claim, String provider) {
        Instant now = clock.instant();
        return new PersistableOutcome.Delivered(now.minusMillis(5), now, provider, claim.id().toString());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("C1 an IN_APP delivery with no registered side effect throws and commits nothing at all")
    void missingInAppHookRollsTheWholeResultBack() {
        UUID id = seedQueued(Channel.IN_APP, "user-no-hook");
        ClaimSnapshot claim = claim(id);
        long historyBefore = count(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", id);

        assertThatThrownBy(() -> persistInTransaction(withNoSideEffects(), claim, delivered(claim, "inapp")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DeliveryResultSideEffect is registered for channel IN_APP");

        // 1. the notification keeps its pre-result state, so the worker can retry or quarantine it
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .as("never DELIVERED without the durable write")
                .isEqualTo("PROCESSING");
        // 2. no successful attempt
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id))
                .as("no attempt row committed")
                .isZero();
        // 3. no additional history row from the rolled-back transition
        assertThat(count("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", id))
                .as("the fenced transition and its history row rolled back together")
                .isEqualTo(historyBefore);
        assertThat(count("""
                SELECT count(*) FROM notification_status_history
                WHERE notification_id = ? AND to_status = 'DELIVERED'
                """, id))
                .isZero();
        // 4. no inbox row - and therefore nothing for the post-commit fan-out to announce, which only ever runs
        //    after this transaction commits (AbstractDeliveryWorker.afterResultCommitted)
        assertThat(count("SELECT count(*) FROM inapp_messages WHERE notification_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM dead_letter_entries WHERE notification_id = ?", id)).isZero();
    }

    @Test
    @DisplayName("EMAIL needs no inbox hook: an empty side-effect list leaves established email behaviour alone")
    void emailDeliversWithNoSideEffectRegistered() {
        UUID id = seedQueued(Channel.EMAIL, "someone@example.com");
        ClaimSnapshot claim = claim(id);

        persistInTransaction(withNoSideEffects(), claim, delivered(claim, "ses"));

        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("DELIVERED");
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("WEBHOOK needs no inbox hook either")
    void webhookDeliversWithNoSideEffectRegistered() {
        UUID id = seedQueued(Channel.WEBHOOK, "https://example.invalid/hook");
        ClaimSnapshot claim = claim(id);

        persistInTransaction(withNoSideEffects(), claim, delivered(claim, "webhook"));

        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("DELIVERED");
        assertThat(count("SELECT count(*) FROM delivery_attempts WHERE notification_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("the registered IN_APP hook still writes the row: the mandatory check did not break the normal path")
    void registeredHookStillWritesTheRow() {
        UUID id = seedQueued(Channel.IN_APP, "user-with-hook");
        ClaimSnapshot claim = claim(id);

        productionService.persist(claim, delivered(claim, "inapp"));

        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("DELIVERED");
        assertThat(count("SELECT count(*) FROM inapp_messages WHERE notification_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("two side effects claiming the same channel are refused at construction, not silently one-of-two")
    void duplicateRegistrationIsRefused() {
        DeliveryResultSideEffect first = stubFor(Channel.IN_APP);
        DeliveryResultSideEffect second = stubFor(Channel.IN_APP);

        assertThatThrownBy(() -> new DeliveryResultService(
                transitioner, notificationService, attemptRepository, newMetrics(), clock, List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two delivery result side effects claim channel IN_APP");

        // Two effects for *different* channels are fine.
        new DeliveryResultService(transitioner, notificationService, attemptRepository, newMetrics(), clock,
                List.of(stubFor(Channel.IN_APP), stubFor(Channel.EMAIL)));
    }

    private static DeliveryResultSideEffect stubFor(Channel channel) {
        return new DeliveryResultSideEffect() {
            @Override
            public Channel channel() {
                return channel;
            }

            @Override
            public void afterFencedDelivery(ClaimSnapshot claim, PersistableOutcome.Delivered outcome) {
                // never invoked in this test
            }
        };
    }
}
