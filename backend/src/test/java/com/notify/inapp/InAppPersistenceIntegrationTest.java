package com.notify.inapp;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.Channel;
import com.notify.delivery.model.FencedOutException;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.inapp.model.InAppMessageView;
import com.notify.inapp.service.InAppMessageService;
import com.notify.inapp.service.InAppMessageWriter;
import com.notify.inapp.service.InAppRecipientLockKey;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In-app persistence inside the checked result transaction, and the ordering guarantee it carries
 * (/ , , ; plan cases O1, O2, O4, O7 and condition C1).
 *
 * <p>Real PostgreSQL, because every property under test is a property of transactions and locks: the fence, the
 * rollback, the advisory lock that makes allocation order equal commit order, and the idempotent conflict path.
 */
class InAppPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DeliveryResultService resultService;
    @Autowired private InAppMessageService messages;
    @Autowired private InAppMessageWriter writer;
    @Autowired private NotificationTransitioner transitioner;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private Clock clock;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(new TenantEntity("InAppOrg", "inapp-org-" + UUID.randomUUID())).getId();
    }

    /** A QUEUED IN_APP notification whose recipient is an opaque reference. */
    private UUID seedQueued(String recipientRef, String subject, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'IN_APP', 'QUEUED', ?, ?, ?, 0, 1, 0, 5, now())
                """, id, tenantId, recipientRef, subject, body);
        return id;
    }

    private ClaimSnapshot claim(UUID notificationId) {
        ClaimResult result = transitioner.claim(tenantId, notificationId, 0, null);
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    private PersistableOutcome.Delivered delivered(ClaimSnapshot claim) {
        Instant now = clock.instant();
        return new PersistableOutcome.Delivered(now.minusMillis(5), now, "inapp", claim.id().toString());
    }

    private List<Map<String, Object>> rows(UUID notificationId) {
        return jdbc.queryForList("SELECT * FROM inapp_messages WHERE notification_id = ?", notificationId);
    }

    // ─── C1: mandatory persistence ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 a delivered IN_APP result commits the inbox row and the status together")
    void deliveredWritesTheRowAtomically() {
        UUID id = seedQueued("user-1", "Order shipped", "On its way");
        ClaimSnapshot claim = claim(id);

        resultService.persist(claim, delivered(claim));

        assertThat(rows(id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("DELIVERED");
        List<Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT outcome, provider FROM delivery_attempts WHERE notification_id = ?", id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("outcome")).isEqualTo(AttemptOutcome.SUCCESS.name());
        assertThat(attempts.get(0).get("provider")).isEqualTo("inapp");
    }

    @Test
    @DisplayName("C1 a fenced-out claim leaves no inbox row: a stale owner can never make a message visible")
    void fencedOutClaimLeavesNoRow() {
        UUID id = seedQueued("user-2", "Subject", "Body");
        ClaimSnapshot stale = claim(id);

        // Someone else supersedes the claim: a second claim bumps claim_seq, so the stale result is fenced out.
        jdbc.update("UPDATE notifications SET claim_seq = claim_seq + 1 WHERE id = ?", id);

        assertThatThrownBy(() -> resultService.persist(stale, delivered(stale)))
                .isInstanceOf(FencedOutException.class);

        assertThat(rows(id)).as("the insert rolled back with the fenced status update").isEmpty();
    }

    @Test
    @DisplayName("C1 a notification with no recipient reference rolls the whole delivery back rather than reporting DELIVERED")
    void missingRecipientRollsBack() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'IN_APP', 'QUEUED', '', 'Subject', 'Body', 0, 1, 0, 5, now())
                """, id, tenantId);
        ClaimSnapshot claim = claim(id);

        assertThatThrownBy(() -> resultService.persist(claim, delivered(claim)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .as("never DELIVERED without a durable row")
                .isEqualTo("PROCESSING");
    }

    // ─── O4: idempotency ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O4 a redelivered dispatch leaves exactly one row and still reports success")
    void redeliveryIsIdempotent() {
        UUID id = seedQueued("user-3", "Subject", "Body");
        ClaimSnapshot claim = claim(id);

        long first = transactions.execute(status -> writer.write(claim));
        long second = transactions.execute(status -> writer.write(claim));

        assertThat(second).as("the same row, not a new one").isEqualTo(first);
        assertThat(rows(id)).hasSize(1);
    }

    // ─── O1: the advisory lock makes allocation order equal commit order ───────────────────────────────────────────

    @Test
    @DisplayName("O1 a stalled lower-sequence transaction blocks the next writer for the same recipient, so commit order equals seq order")
    void sameRecipientWritersSerialiseOnTheAdvisoryLock() throws Exception {
        String recipient = "user-ordering";
        UUID firstId = seedQueued(recipient, "First", "1");
        UUID secondId = seedQueued(recipient, "Second", "2");
        ClaimSnapshot firstClaim = claim(firstId);
        ClaimSnapshot secondClaim = claim(secondId);

        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstSeq = new AtomicLong();
        AtomicLong secondSeq = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writerOne = new Thread(() -> {
            try {
                transactions.executeWithoutResult(status -> {
                    firstSeq.set(writer.write(firstClaim));
                    firstInserted.countDown();
                    try {
                        // Hold the transaction - and therefore the advisory lock - open.
                        releaseFirst.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "writer-one");

        Thread writerTwo = new Thread(() -> {
            try {
                transactions.executeWithoutResult(status -> secondSeq.set(writer.write(secondClaim)));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "writer-two");

        writerOne.start();
        assertThat(firstInserted.await(30, TimeUnit.SECONDS)).isTrue();
        writerTwo.start();

        // While the first transaction holds the lock, the second cannot have inserted anything.
        Thread.sleep(300);
        assertThat(rows(secondId)).as("blocked on the advisory lock, not merely slow").isEmpty();
        assertThat(secondSeq.get()).isZero();

        releaseFirst.countDown();
        writerOne.join(30_000);
        writerTwo.join(30_000);
        assertThat(failure.get()).isNull();

        assertThat(secondSeq.get()).as("allocated after the first committed").isGreaterThan(firstSeq.get());

        // And a reader that sees the second row necessarily sees the first: that is the monotone-commit property.
        List<InAppMessageView> all = messages.ascendingAbove(tenantId, recipient, 0, 10);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).seq()).isEqualTo(firstSeq.get());
        assertThat(all.get(1).seq()).isEqualTo(secondSeq.get());
    }

    @Test
    @DisplayName("O1 different recipients do not contend: their lock keys differ")
    void differentRecipientsUseDifferentLockKeys() {
        long a = InAppRecipientLockKey.of(tenantId, "user-a");
        long b = InAppRecipientLockKey.of(tenantId, "user-b");
        long sameAgain = InAppRecipientLockKey.of(tenantId, "user-a");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).as("stable across calls, so the lock is the same lock every time").isEqualTo(sameAgain);
        assertThat(InAppRecipientLockKey.of(UUID.randomUUID(), "user-a"))
                .as("and tenant-scoped").isNotEqualTo(a);
    }

    // ─── O7: sparse sequences are normal, not gaps ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O7 a recipient's sequences are sparse by construction, and that is not an anomaly")
    void sequencesAreSparsePerRecipient() {
        UUID mine1 = seedQueued("sparse-user", "A", "1");
        UUID other = seedQueued("another-user", "B", "2");
        UUID mine2 = seedQueued("sparse-user", "C", "3");

        transactions.executeWithoutResult(status -> writer.write(claim(mine1)));
        transactions.executeWithoutResult(status -> writer.write(claim(other)));
        transactions.executeWithoutResult(status -> writer.write(claim(mine2)));

        List<InAppMessageView> mine = messages.ascendingAbove(tenantId, "sparse-user", 0, 10);
        assertThat(mine).hasSize(2);
        assertThat(mine.get(1).seq() - mine.get(0).seq())
                .as("another recipient's row sits between them")
                .isGreaterThan(1L);
    }

    // ─── isolation ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reads are scoped by tenant and recipient, so another tenant's message is invisible")
    void readsAreScoped() {
        UUID otherTenant = tenantRepository.save(
                new TenantEntity("OtherOrg", "other-org-" + UUID.randomUUID())).getId();
        UUID id = seedQueued("shared-name", "Mine", "1");
        transactions.executeWithoutResult(status -> writer.write(claim(id)));

        assertThat(messages.ascendingAbove(tenantId, "shared-name", 0, 10)).hasSize(1);
        assertThat(messages.ascendingAbove(otherTenant, "shared-name", 0, 10)).isEmpty();
        assertThat(messages.ascendingAbove(tenantId, "different-name", 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("mark-read is idempotent and refuses a message of another tenant or recipient")
    void markReadIsScopedAndIdempotent() {
        UUID id = seedQueued("reader", "Subject", "Body");
        long seq = transactions.execute(status -> writer.write(claim(id)));

        assertThat(messages.countUnread(tenantId, "reader")).isEqualTo(1);
        assertThat(messages.markRead(tenantId, "reader", seq)).isTrue();
        assertThat(messages.countUnread(tenantId, "reader")).isZero();
        assertThat(messages.markRead(tenantId, "reader", seq)).as("idempotent").isTrue();
        assertThat(messages.markRead(tenantId, "someone-else", seq)).isFalse();
        assertThat(messages.markRead(UUID.randomUUID(), "reader", seq)).isFalse();
    }
}
