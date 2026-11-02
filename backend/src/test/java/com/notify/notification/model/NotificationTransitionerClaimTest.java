package com.notify.notification.model;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O1 (claim predicate), O2 (claim_seq accounting and fencing at transitioner level). */
class NotificationTransitionerClaimTest extends AbstractPostgresIntegrationTest {

    private static final String SENDER = "sender@claim.notify.local";

    @Autowired
    private NotificationTransitioner transitioner;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(new TenantEntity("ClaimOrg", "claim-org-" + UUID.randomUUID())).getId();
    }

    private UUID insert(String status, int generation, int attemptCount, Instant nextRetryAt, String sender) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, next_retry_at, sender, queued_at)
                VALUES (?, ?, 'EMAIL', ?, 'rcpt@claim.notify.local', 'subject', 'body', ?, 1, ?, 5, ?, ?, now())
                """, id, tenantId, status, generation, attemptCount,
                nextRetryAt == null ? null : Timestamp.from(nextRetryAt), sender);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, attempt_count, claim_seq, deferral_count, dispatch_generation, delivery_cycle, next_retry_at,
                       sender, version, processing_started_at, first_processing_at, last_error_code, provider, completed_at
                FROM notifications WHERE id = ?
                """, id);
    }

    private int historyCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Integer.class, id);
    }

    private int num(Map<String, Object> r, String key) {
        return ((Number) r.get(key)).intValue();
    }

    private ClaimSnapshot claimed(ClaimResult result) {
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    @Test
    @DisplayName("O1 QUEUED at the expected generation is claimed: PROCESSING, attempt+1, claim_seq+1, one history row")
    void claimsQueued() {
        UUID id = insert("QUEUED", 3, 0, null, null);
        int versionBefore = num(row(id), "version");

        ClaimResult result = transitioner.claim(tenantId, id, 3, SENDER);

        ClaimSnapshot snap = claimed(result);
        assertThat(((ClaimResult.Claimed) result).fromStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(snap.id()).isEqualTo(id);
        assertThat(snap.tenantId()).isEqualTo(tenantId);
        assertThat(snap.generation()).isEqualTo(3);
        assertThat(snap.claimSeq()).isEqualTo(1);
        assertThat(snap.attemptCount()).isEqualTo(1);
        assertThat(snap.maxAttempts()).isEqualTo(5);
        assertThat(snap.deferralCount()).isZero();
        assertThat(snap.deliveryCycle()).isEqualTo(1);
        assertThat(snap.recipient()).isEqualTo("rcpt@claim.notify.local");
        assertThat(snap.sender()).isEqualTo(SENDER);
        assertThat(snap.toString()).doesNotContain("rcpt@").doesNotContain("body");

        Map<String, Object> r = row(id);
        assertThat(r.get("status")).isEqualTo("PROCESSING");
        assertThat(num(r, "attempt_count")).isEqualTo(1);
        assertThat(num(r, "claim_seq")).isEqualTo(1);
        assertThat(num(r, "dispatch_generation")).as("claim does not advance the dispatch generation").isEqualTo(3);
        assertThat(num(r, "delivery_cycle")).as("claim does not touch delivery_cycle").isEqualTo(1);
        assertThat(num(r, "version")).isEqualTo(versionBefore + 1);
        assertThat(r.get("processing_started_at")).isNotNull();
        assertThat(r.get("first_processing_at")).isNotNull();
        assertThat(r.get("next_retry_at")).isNull();

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT from_status, to_status, actor FROM notification_status_history WHERE notification_id = ?", id);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("from_status", "QUEUED").containsEntry("to_status", "PROCESSING").containsEntry("actor", "SYSTEM");
    }

    @Test
    @DisplayName("O1 RETRYING is claimed only when due; the claim clears next_retry_at and increments attempt and claim_seq")
    void claimsDueRetryingOnly() {
        UUID due = insert("RETRYING", 4, 1, Instant.now().minus(1, ChronoUnit.MINUTES), SENDER);
        UUID notDue = insert("RETRYING", 4, 1, Instant.now().plus(10, ChronoUnit.MINUTES), SENDER);

        ClaimResult dueResult = transitioner.claim(tenantId, due, 4, SENDER);
        assertThat(((ClaimResult.Claimed) dueResult).fromStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(claimed(dueResult).attemptCount()).isEqualTo(2);
        Map<String, Object> dueRow = row(due);
        assertThat(dueRow.get("status")).isEqualTo("PROCESSING");
        assertThat(dueRow.get("next_retry_at")).isNull();
        assertThat(num(dueRow, "claim_seq")).isEqualTo(1);

        Map<String, Object> before = row(notDue);
        assertThat(transitioner.claim(tenantId, notDue, 4, SENDER)).isSameAs(ClaimResult.Unclaimed.INSTANCE);
        assertThat(row(notDue)).as("a not-due RETRYING row is untouched").isEqualTo(before);
        assertThat(historyCount(notDue)).isZero();
    }

    @Test
    @DisplayName("O1 wrong generation, wrong tenant, missing row and every non-claimable status yield Unclaimed with no side effects")
    void unclaimedCases() {
        UUID queued = insert("QUEUED", 2, 0, null, null);
        Map<String, Object> before = row(queued);

        assertThat(transitioner.claim(tenantId, queued, 1, SENDER)).as("older generation").isSameAs(ClaimResult.Unclaimed.INSTANCE);
        assertThat(transitioner.claim(tenantId, queued, 3, SENDER)).as("newer generation").isSameAs(ClaimResult.Unclaimed.INSTANCE);
        assertThat(transitioner.claim(UUID.randomUUID(), queued, 2, SENDER)).as("wrong tenant").isSameAs(ClaimResult.Unclaimed.INSTANCE);
        assertThat(transitioner.claim(tenantId, UUID.randomUUID(), 2, SENDER)).as("missing row").isSameAs(ClaimResult.Unclaimed.INSTANCE);
        assertThat(row(queued)).isEqualTo(before);
        assertThat(historyCount(queued)).isZero();

        for (String status : List.of("CREATED", "SCHEDULED", "PROCESSING", "DELIVERED", "FAILED", "DLQ", "CANCELLED")) {
            UUID id = insert(status, 2, 1, null, null);
            Map<String, Object> rowBefore = row(id);
            assertThat(transitioner.claim(tenantId, id, 2, SENDER)).as(status).isSameAs(ClaimResult.Unclaimed.INSTANCE);
            assertThat(row(id)).as(status + " untouched").isEqualTo(rowBefore);
        }
    }

    @Test
    @DisplayName("O1 an Unclaimed result does not mark an enclosing transaction rollback-only")
    void unclaimedIsNotRollbackOnly() {
        UUID missing = UUID.randomUUID();
        UUID other = insert("QUEUED", 1, 0, null, null);
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        Boolean committed = tt.execute(status -> {
            assertThat(transitioner.claim(tenantId, missing, 1, SENDER)).isSameAs(ClaimResult.Unclaimed.INSTANCE);
            assertThat(status.isRollbackOnly()).isFalse();
            claimed(transitioner.claim(tenantId, other, 1, SENDER));
            return true;
        });
        assertThat(committed).isTrue();
        assertThat(row(other).get("status")).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("O2 claim_seq grows by one per claim; results and the deferral refund never change it; stale owner writes update 0 rows")
    void claimSeqAccountingAndFencing() {
        UUID id = insert("QUEUED", 1, 0, null, null);

        // Owner A
        ClaimSnapshot a = claimed(transitioner.claim(tenantId, id, 1, SENDER));
        assertThat(a.claimSeq()).isEqualTo(1);
        assertThat(a.attemptCount()).isEqualTo(1);

        // A defers: attempt refunded, deferral counted, claim_seq untouched
        Instant dueSoon = Instant.now().minus(1, ChronoUnit.SECONDS);
        assertThat(transitioner.deferDelivery(tenantId, id, 1, a.claimSeq(), dueSoon, "DEFERRED")).isTrue();
        Map<String, Object> afterDefer = row(id);
        assertThat(afterDefer.get("status")).isEqualTo("RETRYING");
        assertThat(num(afterDefer, "attempt_count")).isZero();
        assertThat(num(afterDefer, "deferral_count")).isEqualTo(1);
        assertThat(num(afterDefer, "claim_seq")).isEqualTo(1);
        assertThat(afterDefer.get("last_error_code")).isEqualTo("DEFERRED");

        // Owner B reclaims at the SAME generation: same attempt number as A had, but a new claim_seq
        ClaimSnapshot b = claimed(transitioner.claim(tenantId, id, 1, SENDER));
        assertThat(b.attemptCount()).as("attempt_count alone cannot identify a claim").isEqualTo(a.attemptCount());
        assertThat(b.claimSeq()).isEqualTo(2);

        // A's late results of every kind are fenced out and change nothing
        Map<String, Object> beforeStale = row(id);
        int historyBefore = historyCount(id);
        Instant later = Instant.now().plus(1, ChronoUnit.MINUTES);
        assertThat(transitioner.completeDelivery(tenantId, id, 1, a.claimSeq(), "fake", "msg-stale")).isFalse();
        assertThat(transitioner.failDelivery(tenantId, id, 1, a.claimSeq(), "STALE")).isFalse();
        assertThat(transitioner.retryDelivery(tenantId, id, 1, a.claimSeq(), later, "STALE")).isFalse();
        assertThat(transitioner.deferDelivery(tenantId, id, 1, a.claimSeq(), later, "STALE")).isFalse();
        assertThat(row(id)).as("B's PROCESSING row is unchanged by A's late writes").isEqualTo(beforeStale);
        assertThat(historyCount(id)).isEqualTo(historyBefore);

        // Wrong generation is fenced out too
        assertThat(transitioner.completeDelivery(tenantId, id, 2, b.claimSeq(), "fake", "msg")).isFalse();

        // B's own result succeeds and claim_seq stays 2
        assertThat(transitioner.completeDelivery(tenantId, id, 1, b.claimSeq(), "fake", "msg-b")).isTrue();
        Map<String, Object> done = row(id);
        assertThat(done.get("status")).isEqualTo("DELIVERED");
        assertThat(done.get("provider")).isEqualTo("fake");
        assertThat(done.get("completed_at")).isNotNull();
        assertThat(num(done, "claim_seq")).isEqualTo(2);

        // A terminal row is not claimable again
        assertThat(transitioner.claim(tenantId, id, 1, SENDER)).isSameAs(ClaimResult.Unclaimed.INSTANCE);
    }

    @Test
    @DisplayName("O2 retry and failure results are fenced and write the documented columns")
    void retryAndFailResults() {
        UUID retryId = insert("QUEUED", 1, 0, null, null);
        ClaimSnapshot c = claimed(transitioner.claim(tenantId, retryId, 1, SENDER));
        Instant next = Instant.now().plus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        assertThat(transitioner.retryDelivery(tenantId, retryId, 1, c.claimSeq(), next, "PROVIDER_UNAVAILABLE")).isTrue();
        Map<String, Object> r = row(retryId);
        assertThat(r.get("status")).isEqualTo("RETRYING");
        assertThat(((Timestamp) r.get("next_retry_at")).toInstant()).isEqualTo(next);
        assertThat(r.get("last_error_code")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(num(r, "attempt_count")).isEqualTo(1);
        assertThat(num(r, "claim_seq")).isEqualTo(1);

        UUID failId = insert("QUEUED", 1, 0, null, null);
        ClaimSnapshot f = claimed(transitioner.claim(tenantId, failId, 1, SENDER));
        assertThat(transitioner.failDelivery(tenantId, failId, 1, f.claimSeq(), "AUTHENTICATION_FAILED")).isTrue();
        Map<String, Object> fr = row(failId);
        assertThat(fr.get("status")).isEqualTo("FAILED");
        assertThat(fr.get("last_error_code")).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(fr.get("next_retry_at")).isNull();
        assertThat(fr.get("completed_at")).isNotNull();
        // history: QUEUED->PROCESSING, PROCESSING->FAILED
        assertThat(historyCount(failId)).isEqualTo(2);
    }

    @Test
    @DisplayName("S2 a legacy row without sender gets the configured sender once at first claim and keeps it for every later claim")
    void legacySenderFrozenAtFirstClaim() {
        UUID id = insert("QUEUED", 1, 0, null, null);
        assertThat(row(id).get("sender")).isNull();

        ClaimSnapshot first = claimed(transitioner.claim(tenantId, id, 1, "first@claim.notify.local"));
        assertThat(first.sender()).isEqualTo("first@claim.notify.local");
        assertThat(transitioner.retryDelivery(tenantId, id, 1, first.claimSeq(), Instant.now().minusSeconds(1), "PROVIDER_UNAVAILABLE")).isTrue();

        // configuration changed in the meantime: the stored sender wins
        ClaimSnapshot second = claimed(transitioner.claim(tenantId, id, 1, "changed@claim.notify.local"));
        assertThat(second.sender()).isEqualTo("first@claim.notify.local");
        assertThat(row(id).get("sender")).isEqualTo("first@claim.notify.local");

        // a row that already has a sender (accepted after V6) is never overwritten
        UUID accepted = insert("QUEUED", 1, 0, null, "accepted@claim.notify.local");
        assertThat(claimed(transitioner.claim(tenantId, accepted, 1, "other@claim.notify.local")).sender())
                .isEqualTo("accepted@claim.notify.local");
    }
}
