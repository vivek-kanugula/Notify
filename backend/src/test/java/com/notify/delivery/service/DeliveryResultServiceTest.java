package com.notify.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.DlqReason;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.model.FencedOutException;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.event.NotificationTransitionedEvent;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P4: atomic result persistence, fencing, DLQ outbox event, event published once after commit. */
class DeliveryResultServiceTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static class EventCollector {
        final List<NotificationTransitionedEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(NotificationTransitionedEvent event) {
            events.add(event);
        }

        List<NotificationTransitionedEvent> forNotification(UUID id) {
            return events.stream().filter(e -> e.notificationId().equals(id)).toList();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        EventCollector eventCollector() {
            return new EventCollector();
        }
    }

    @Autowired
    private DeliveryResultService resultService;

    @Autowired
    private NotificationTransitioner transitioner;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventCollector collector;

    private UUID tenantId;
    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(new TenantEntity("ResultOrg", "result-org-" + UUID.randomUUID())).getId();
    }

    /** Same fixture as claimNew, for another channel, so the per-channel snippet limit can be exercised (F6). */
    private ClaimSnapshot claimNew(int maxAttempts, String channel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, ?, 'QUEUED', 'https://receiver.example.com/hook',
                        'subject', 'body', 2, 1, 0, ?, now())
                """, id, tenantId, channel, maxAttempts);
        ClaimResult result = transitioner.claim(tenantId, id, 2, null);
        return ((ClaimResult.Claimed) result).snapshot();
    }

    @Test
    @DisplayName("F6 the stored response snippet is limited per channel: EMAIL 1024 characters, WEBHOOK the full column")
    void snippetLimitIsPerChannel() {
        String oversize = "x".repeat(5_000);

        ClaimSnapshot email = claimNew(5);
        resultService.persist(email, new PersistableOutcome.Retry(T0, T0, "resend",
                "PROVIDER_UNAVAILABLE", "m", 503, oversize, T0.plusSeconds(30)));

        ClaimSnapshot webhook = claimNew(5, "WEBHOOK");
        resultService.persist(webhook, new PersistableOutcome.Retry(T0, T0, "webhook",
                "WEBHOOK_SERVER_ERROR", "m", 503, oversize, T0.plusSeconds(30)));

        Integer emailLength = jdbc.queryForObject(
                "SELECT length(response_snippet) FROM delivery_attempts WHERE notification_id = ?",
                Integer.class, email.id());
        Integer webhookLength = jdbc.queryForObject(
                "SELECT length(response_snippet) FROM delivery_attempts WHERE notification_id = ?",
                Integer.class, webhook.id());

        assertThat(emailLength).as("email behaviour is unchanged").isEqualTo(1024);
        assertThat(webhookLength)
                .as("a webhook snippet may use the full VARCHAR(4096) column")
                .isEqualTo(4096);
    }
    private ClaimSnapshot claimNew(int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, sender, queued_at)
                VALUES (?, ?, 'EMAIL', 'QUEUED', 'rcpt@result.notify.local', 'subject', 'body', 2, 1, 0, ?, 'from@result.notify.local', now())
                """, id, tenantId, maxAttempts);
        ClaimResult result = transitioner.claim(tenantId, id, 2, "from@result.notify.local");
        return ((ClaimResult.Claimed) result).snapshot();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, attempt_count, claim_seq, deferral_count, next_retry_at, last_error_code,
                       provider, provider_message_id, completed_at, version, dispatch_generation, delivery_cycle
                FROM notifications WHERE id = ?""", id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("SELECT * FROM delivery_attempts WHERE notification_id = ? ORDER BY attempt_number", id);
    }

    private int historyCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Integer.class, id);
    }

    private List<Map<String, Object>> dlqOutbox(UUID id) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ? AND topic = 'notify.dlq.v1'", id);
    }

    @Test
    @DisplayName("P4 DELIVERED: status, provider fields, one SUCCESS attempt, two history rows, the event published once after commit")
    void delivered() {
        ClaimSnapshot claim = claimNew(5);
        Instant started = T0;
        Instant finished = T0.plusMillis(250);

        resultService.persist(claim, new PersistableOutcome.Delivered(started, finished, "fake", "fake_msg_1"));

        Map<String, Object> r = row(claim.id());
        assertThat(r.get("status")).isEqualTo("DELIVERED");
        assertThat(r.get("provider")).isEqualTo("fake");
        assertThat(r.get("provider_message_id")).isEqualTo("fake_msg_1");
        assertThat(r.get("completed_at")).isNotNull();

        List<Map<String, Object>> attempts = attempts(claim.id());
        assertThat(attempts).hasSize(1);
        Map<String, Object> a = attempts.get(0);
        assertThat(a.get("outcome")).isEqualTo("SUCCESS");
        assertThat(((Number) a.get("attempt_number")).intValue()).isEqualTo(claim.attemptCount());
        assertThat(((Number) a.get("delivery_cycle")).intValue()).isEqualTo(claim.deliveryCycle());
        assertThat(a.get("provider")).isEqualTo("fake");
        assertThat(((Timestamp) a.get("started_at")).toInstant()).isEqualTo(started);
        assertThat(((Timestamp) a.get("finished_at")).toInstant()).isEqualTo(finished);
        assertThat(((Number) a.get("latency_ms")).longValue()).isEqualTo(250L);

        assertThat(historyCount(claim.id())).isEqualTo(2);
        assertThat(dlqOutbox(claim.id())).isEmpty();
        List<NotificationTransitionedEvent> events = collector.forNotification(claim.id());
        assertThat(events).filteredOn(e -> e.toStatus() == NotificationStatus.DELIVERED).hasSize(1);
        assertThat(events).filteredOn(e -> e.toStatus() == NotificationStatus.DELIVERED)
                .allSatisfy(e -> assertThat(e.fromStatus()).isEqualTo(NotificationStatus.PROCESSING));
    }

    @Test
    @DisplayName("P4 RETRYING: next_retry_at and error code stored, RETRYABLE_FAILURE attempt with status, message and snippet")
    void retry() {
        ClaimSnapshot claim = claimNew(5);
        Instant next = T0.plus(2, ChronoUnit.MINUTES);

        resultService.persist(claim, new PersistableOutcome.Retry(T0, T0.plusMillis(40), "resend",
                "PROVIDER_UNAVAILABLE", "down", 503, "snippet", next));

        Map<String, Object> r = row(claim.id());
        assertThat(r.get("status")).isEqualTo("RETRYING");
        assertThat(((Timestamp) r.get("next_retry_at")).toInstant()).isEqualTo(next);
        assertThat(r.get("last_error_code")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(((Number) r.get("attempt_count")).intValue()).isEqualTo(1);

        Map<String, Object> a = attempts(claim.id()).get(0);
        assertThat(a.get("outcome")).isEqualTo("RETRYABLE_FAILURE");
        assertThat(a.get("error_code")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(a.get("error_message")).isEqualTo("down");
        assertThat(((Number) a.get("http_status")).intValue()).isEqualTo(503);
        assertThat(a.get("response_snippet")).isEqualTo("snippet");
        assertThat(dlqOutbox(claim.id())).isEmpty();
    }

    @Test
    @DisplayName("P4 permanent failure: FAILED, PERMANENT_FAILURE attempt, one PENDING dead-letter outbox row with the CONTRACTS envelope")
    void permanentFailure() throws Exception {
        ClaimSnapshot claim = claimNew(5);

        resultService.persist(claim, new PersistableOutcome.Fail(T0, T0.plusMillis(30), "resend",
                AttemptOutcome.PERMANENT_FAILURE, DlqReason.PERMANENT_FAILURE,
                "AUTHENTICATION_FAILED", "bad key", 401, null));

        Map<String, Object> r = row(claim.id());
        assertThat(r.get("status")).isEqualTo("FAILED");
        assertThat(r.get("last_error_code")).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(r.get("next_retry_at")).isNull();
        assertThat(r.get("completed_at")).isNotNull();
        assertThat(attempts(claim.id()).get(0).get("outcome")).isEqualTo("PERMANENT_FAILURE");

        List<Map<String, Object>> outbox = dlqOutbox(claim.id());
        assertThat(outbox).hasSize(1);
        Map<String, Object> o = outbox.get(0);
        assertThat(o.get("status")).isEqualTo("PENDING");
        assertThat(o.get("event_type")).isEqualTo("NOTIFICATION_DEAD_LETTERED");
        assertThat(o.get("message_key")).isEqualTo(claim.id().toString());
        assertThat(o.get("aggregate_type")).isEqualTo("NOTIFICATION");
        JsonNode payload = MAPPER.readTree(o.get("payload").toString());
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("eventType").asText()).isEqualTo("NOTIFICATION_DEAD_LETTERED");
        assertThat(payload.get("notificationId").asText()).isEqualTo(claim.id().toString());
        assertThat(payload.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(payload.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(payload.get("generation").asInt()).isEqualTo(2);
        assertThat(payload.get("deliveryCycle").asInt()).isEqualTo(1);
        assertThat(payload.get("reason").asText()).isEqualTo("PERMANENT_FAILURE");
        assertThat(payload.get("lastErrorCode").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(payload.get("attemptCount").asInt()).isEqualTo(claim.attemptCount());
        assertThat(payload.has("occurredAt")).isTrue();
        assertThat(payload.toString()).doesNotContain("rcpt@").doesNotContain("body");
    }

    @Test
    @DisplayName("P4 exhausted retries: FAILED with a RETRYABLE_FAILURE attempt and reason MAX_RETRIES_EXCEEDED")
    void exhausted() throws Exception {
        ClaimSnapshot claim = claimNew(1);

        resultService.persist(claim, new PersistableOutcome.Fail(T0, T0.plusMillis(30), "resend",
                AttemptOutcome.RETRYABLE_FAILURE, DlqReason.MAX_RETRIES_EXCEEDED, "RATE_LIMITED", "quota", 429, null));

        assertThat(row(claim.id()).get("status")).isEqualTo("FAILED");
        assertThat(attempts(claim.id()).get(0).get("outcome")).isEqualTo("RETRYABLE_FAILURE");
        JsonNode payload = MAPPER.readTree(dlqOutbox(claim.id()).get(0).get("payload").toString());
        assertThat(payload.get("reason").asText()).isEqualTo("MAX_RETRIES_EXCEEDED");
    }

    @Test
    @DisplayName("T1 tenant not active: system attempt row with zero latency and DLQ reason TENANT_NOT_ACTIVE")
    void tenantNotActive() throws Exception {
        ClaimSnapshot claim = claimNew(5);

        resultService.persist(claim, new PersistableOutcome.Fail(T0, T0, DeliveryOrchestrator.SYSTEM_PROVIDER,
                AttemptOutcome.PERMANENT_FAILURE, DlqReason.TENANT_NOT_ACTIVE,
                "TENANT_NOT_ACTIVE", "Tenant is SUSPENDED", null, null));

        Map<String, Object> a = attempts(claim.id()).get(0);
        assertThat(a.get("provider")).isEqualTo("notify-system");
        assertThat(((Number) a.get("latency_ms")).longValue()).isZero();
        assertThat(row(claim.id()).get("status")).isEqualTo("FAILED");
        JsonNode payload = MAPPER.readTree(dlqOutbox(claim.id()).get(0).get("payload").toString());
        assertThat(payload.get("reason").asText()).isEqualTo("TENANT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("R3 deferral: back to RETRYING, attempt refunded, deferral counted, no attempt row, no DLQ row")
    void deferral() {
        ClaimSnapshot claim = claimNew(5);
        Instant next = T0.plusSeconds(45);

        resultService.persist(claim, new PersistableOutcome.Defer(T0, T0, "FAKE_DEFERRED", next));

        Map<String, Object> r = row(claim.id());
        assertThat(r.get("status")).isEqualTo("RETRYING");
        assertThat(((Number) r.get("attempt_count")).intValue()).isZero();
        assertThat(((Number) r.get("deferral_count")).intValue()).isEqualTo(1);
        assertThat(((Number) r.get("claim_seq")).intValue()).isEqualTo(claim.claimSeq());
        assertThat(((Timestamp) r.get("next_retry_at")).toInstant()).isEqualTo(next);
        assertThat(attempts(claim.id())).isEmpty();
        assertThat(dlqOutbox(claim.id())).isEmpty();
        assertThat(historyCount(claim.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("P4 a stale owner is fenced out: FencedOutException and nothing is written (no attempt, no outbox, no history, no event)")
    void staleOwnerWritesNothing() {
        ClaimSnapshot stale = claimNew(5);
        // owner A defers; owner B reclaims at the same generation with a higher claim_seq
        resultService.persist(stale, new PersistableOutcome.Defer(T0, T0, "FAKE_DEFERRED", Instant.now().minusSeconds(1)));
        ClaimSnapshot current = ((ClaimResult.Claimed) transitioner.claim(tenantId, stale.id(), 2, "from@result.notify.local")).snapshot();
        assertThat(current.claimSeq()).isEqualTo(stale.claimSeq() + 1);
        assertThat(current.attemptCount()).as("refunded attempt number is reused").isEqualTo(stale.attemptCount());

        Map<String, Object> before = row(stale.id());
        int historyBefore = historyCount(stale.id());
        int eventsBefore = collector.forNotification(stale.id()).size();

        for (PersistableOutcome outcome : List.<PersistableOutcome>of(
                new PersistableOutcome.Delivered(T0, T0, "fake", "late"),
                new PersistableOutcome.Retry(T0, T0, "fake", "E", "m", null, null, T0.plusSeconds(60)),
                new PersistableOutcome.Defer(T0, T0, "E", T0.plusSeconds(60)),
                new PersistableOutcome.Fail(T0, T0, "fake", AttemptOutcome.PERMANENT_FAILURE, DlqReason.PERMANENT_FAILURE, "E", "m", null, null))) {
            assertThatThrownBy(() -> resultService.persist(stale, outcome))
                    .as(outcome.getClass().getSimpleName()).isInstanceOf(FencedOutException.class);
        }

        assertThat(row(stale.id())).as("owner B's PROCESSING row is untouched").isEqualTo(before);
        assertThat(attempts(stale.id())).isEmpty();
        assertThat(dlqOutbox(stale.id())).isEmpty();
        assertThat(historyCount(stale.id())).isEqualTo(historyBefore);
        assertThat(collector.forNotification(stale.id())).hasSize(eventsBefore);

        // the current owner can still finish
        resultService.persist(current, new PersistableOutcome.Delivered(T0, T0, "fake", "good"));
        assertThat(row(stale.id()).get("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("P4 atomicity: a failure after the status update and history rolls everything back (attempt insert violates a constraint)")
    void rollbackWhenAttemptInsertFails() {
        ClaimSnapshot claim = claimNew(5);
        // occupy the (notification, cycle, attempt_number) slot so the attempt insert must fail after the status update
        jdbc.update("""
                INSERT INTO delivery_attempts (tenant_id, notification_id, delivery_cycle, attempt_number, provider,
                    started_at, finished_at, outcome, latency_ms)
                VALUES (?, ?, ?, ?, 'blocker', now(), now(), 'SUCCESS', 0)
                """, tenantId, claim.id(), claim.deliveryCycle(), claim.attemptCount());
        Map<String, Object> before = row(claim.id());
        int historyBefore = historyCount(claim.id());
        int eventsBefore = collector.forNotification(claim.id()).size();

        assertThatThrownBy(() -> resultService.persist(claim, new PersistableOutcome.Delivered(T0, T0, "fake", "msg")))
                .isNotInstanceOf(FencedOutException.class);

        assertThat(row(claim.id())).as("status update rolled back").isEqualTo(before);
        assertThat(row(claim.id()).get("status")).isEqualTo("PROCESSING");
        assertThat(historyCount(claim.id())).as("history row rolled back").isEqualTo(historyBefore);
        assertThat(attempts(claim.id())).hasSize(1);
        assertThat(collector.forNotification(claim.id())).as("no event for a rolled-back transaction").hasSize(eventsBefore);
    }

    @Test
    @DisplayName("P4 atomicity: a failure writing the dead-letter outbox row rolls back the status, history and attempt")
    void rollbackWhenDeadLetterInsertFails() {
        ClaimSnapshot claim = claimNew(5);
        String function = "fail_dlq_" + claim.id().toString().replace('-', '_');
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.aggregate_id = '" + claim.id() + "'::uuid THEN RAISE EXCEPTION 'injected dlq failure'; END IF; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            Map<String, Object> before = row(claim.id());
            int historyBefore = historyCount(claim.id());
            int eventsBefore = collector.forNotification(claim.id()).size();

            assertThatThrownBy(() -> resultService.persist(claim, new PersistableOutcome.Fail(T0, T0, "resend",
                    AttemptOutcome.PERMANENT_FAILURE, DlqReason.PERMANENT_FAILURE, "VALIDATION_FAILED", "m", 422, null)))
                    .hasStackTraceContaining("injected dlq failure");

            assertThat(row(claim.id())).isEqualTo(before);
            assertThat(historyCount(claim.id())).isEqualTo(historyBefore);
            assertThat(attempts(claim.id())).isEmpty();
            assertThat(dlqOutbox(claim.id())).isEmpty();
            assertThat(collector.forNotification(claim.id())).hasSize(eventsBefore);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    @Test
    @DisplayName("P4 stored attempt text is bounded: code 64, message 1024, snippet 1024")
    void textBounds() {
        ClaimSnapshot claim = claimNew(5);
        resultService.persist(claim, new PersistableOutcome.Retry(T0, T0.plusMillis(5), "resend",
                "C".repeat(200), "m".repeat(5_000), 500, "s".repeat(5_000), T0.plusSeconds(60)));

        Map<String, Object> a = attempts(claim.id()).get(0);
        assertThat(a.get("error_code").toString()).hasSize(64);
        assertThat(a.get("error_message").toString()).hasSize(1024);
        assertThat(a.get("response_snippet").toString()).hasSize(1024);
        assertThat(row(claim.id()).get("last_error_code").toString()).as("notification column is VARCHAR(64) too").hasSize(64);
    }

    @Test
    @DisplayName("P4 the dead-letter event can only be written inside the result transaction")
    void deadLetterRequiresTransaction() {
        assertThatThrownBy(() -> notificationService.recordDeadLetter(
                tenantId, UUID.randomUUID(), com.notify.common.model.Channel.EMAIL, 1, 1, 1,
                DlqReason.PERMANENT_FAILURE, "E", null, T0))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
