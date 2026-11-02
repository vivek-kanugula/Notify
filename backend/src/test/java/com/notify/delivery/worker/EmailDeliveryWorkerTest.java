package com.notify.delivery.worker;

import com.notify.common.config.MailProperties;
import com.notify.common.db.WorkerPoolScope;
import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.model.FencedOutException;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.ClaimDispositionClassifier;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.delivery.service.ReconciliationEvaluator;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.dto.NotificationEvidence;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationSchedulerService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A1 (acknowledgment ownership, post-commit separation), O4/O5/K8/K9 unit halves, poison parsing, scope cleanup. */
class EmailDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NOTIFICATION = UUID.randomUUID();

    private final NotificationTransitioner transitioner = mock(NotificationTransitioner.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final DeliveryOrchestrator orchestrator = mock(DeliveryOrchestrator.class);
    private final DeliveryResultService resultService = mock(DeliveryResultService.class);
    private final NotificationSchedulerService scheduler = mock(NotificationSchedulerService.class);
    private final WorkerContainerController controller = mock(WorkerContainerController.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeliveryExceptionClassifier classifier = new DeliveryExceptionClassifier();
    private EmailDeliveryWorker worker;

    private final ClaimSnapshot claim = new ClaimSnapshot(NOTIFICATION, TENANT, 3, 7, 1, 2, 5, 0, Channel.EMAIL,
            "to@example.com", "subject", "body", null, "00-trace-span-01", "from@example.com", java.time.Instant.parse("2026-10-11T12:00:00Z"), java.time.Instant.parse("2026-10-11T11:00:00Z"));

    @BeforeEach
    void setUp() {
        worker = new EmailDeliveryWorker(transitioner, notificationService, orchestrator, resultService,
                new ClaimDispositionClassifier(), new ReconciliationEvaluator(), classifier, scheduler, controller,
                new MailProperties("default-sender@example.com"), Clock.fixed(NOW, ZoneOffset.UTC), meters,
                new long[]{0, 0, 0});
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static String payload(String eventType, String channel, int generation) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + eventType
                + "\",\"notificationId\":\"" + NOTIFICATION + "\",\"tenantId\":\"" + TENANT + "\",\"channel\":\"" + channel
                + "\",\"generation\":" + generation + ",\"attempt\":1,\"occurredAt\":\"2026-10-11T12:00:00Z\"}";
    }

    private static ConsumerRecord<String, byte[]> record(String json) {
        ConsumerRecord<String, byte[]> r = new ConsumerRecord<>("notify.dispatch.email.v1", 1, 42L,
                NOTIFICATION.toString(), json == null ? null : json.getBytes(StandardCharsets.UTF_8));
        r.headers().add(new RecordHeader("traceparent", "00-hdr-trace-01".getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    private static ConsumerRecord<String, byte[]> valid() {
        return record(payload("NOTIFICATION_QUEUED", "EMAIL", 3));
    }

    private void claimed() {
        when(transitioner.claim(eq(TENANT), eq(NOTIFICATION), eq(3), any()))
                .thenReturn(new ClaimResult.Claimed(claim, NotificationStatus.QUEUED));
    }

    private void unclaimed(NotificationEvidence evidence) {
        when(transitioner.claim(eq(TENANT), eq(NOTIFICATION), anyInt(), any())).thenReturn(ClaimResult.Unclaimed.INSTANCE);
        when(notificationService.getClaimEvidence(TENANT, NOTIFICATION)).thenReturn(Optional.ofNullable(evidence));
    }

    private static NotificationEvidence evidence(NotificationStatus status, int generation, int claimSeq, Instant nextRetryAt) {
        return new NotificationEvidence(status, generation, claimSeq, 1, 2, 5, 0, nextRetryAt, null);
    }

    private static final PersistableOutcome DELIVERED = new PersistableOutcome.Delivered(NOW, NOW, "fake", "msg");
    private static final PersistableOutcome RETRY = new PersistableOutcome.Retry(NOW, NOW, "fake", "PROVIDER_UNAVAILABLE", "m", 503, null, NOW.plusSeconds(30));
    private static final PersistableOutcome DEFER = new PersistableOutcome.Defer(NOW, NOW, "FAKE_DEFERRED", NOW.plusSeconds(10));
    private static final PersistableOutcome FAIL = new PersistableOutcome.Fail(NOW, NOW, "fake", AttemptOutcome.PERMANENT_FAILURE,
            DlqReason.PERMANENT_FAILURE, "VALIDATION_FAILED", "m", 422, null);

    // ─── poison parsing ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A1 malformed or contract-violating payloads are POISON, are never claimed, and are never acknowledged by the worker")
    void poisonPayloads() {
        List<String> bad = new ArrayList<>(List.of(
                "", "not json", "{}", "[]", "null", "{\"schemaVersion\":1}",
                payload("NOTIFICATION_QUEUED", "WEBHOOK", 3),
                payload("NOTIFICATION_QUEUED", "EMAIL", -1),
                payload("SOMETHING_ELSE", "EMAIL", 3),
                payload("NOTIFICATION_QUEUED", "EMAIL", 3).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                payload("NOTIFICATION_QUEUED", "EMAIL", 3).replace(NOTIFICATION.toString(), "not-a-uuid"),
                payload("NOTIFICATION_QUEUED", "EMAIL", 3).replace("\"generation\":3,", "")));
        for (String json : bad) {
            assertThatThrownBy(() -> worker.onMessage(record(json), ack))
                    .as(json).isInstanceOf(WorkerExceptions.Poison.class);
        }
        assertThatThrownBy(() -> worker.onMessage(record(null), ack)).isInstanceOf(WorkerExceptions.Poison.class);
        verifyNoInteractions(transitioner, orchestrator, resultService, ack);
    }

    @Test
    @DisplayName("A1 unknown extra fields are ignored and all three event types are accepted")
    void validEnvelopes() {
        for (String type : List.of("NOTIFICATION_QUEUED", "NOTIFICATION_RETRY_DUE", "NOTIFICATION_REPLAYED")) {
            claimed();
            when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
            worker.onMessage(record(payload(type, "EMAIL", 3).replace("}", ",\"futureField\":\"x\"}")), ack);
        }
        verify(ack, times(3)).acknowledge();
    }

    // ─── claimed path ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A1 claimed: deliver once, persist, then acknowledge once, in that order; the default sender is offered to the claim")
    void happyPathOrder() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);

        worker.onMessage(valid(), ack);

        InOrder order = inOrder(transitioner, orchestrator, resultService, ack, controller);
        order.verify(transitioner).claim(TENANT, NOTIFICATION, 3, "default-sender@example.com");
        order.verify(orchestrator).deliver(claim);
        order.verify(resultService).persist(claim, DELIVERED);
        order.verify(ack).acknowledge();
        order.verify(controller).onProgress(new TopicPartition("notify.dispatch.email.v1", 1));
        verify(scheduler, never()).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());
        assertThat(meters.counter("notify.delivery.results", "channel", "email", "outcome", "delivered").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A1/K9 retry and deferral schedule the Quartz trigger after the commit (when missing), then acknowledge")
    void retryAndDeferScheduleTriggerAfterCommit() {
        for (PersistableOutcome outcome : List.of(RETRY, DEFER)) {
            claimed();
            when(orchestrator.deliver(claim)).thenReturn(outcome);
            when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(false);
            Instant due = outcome instanceof PersistableOutcome.Retry r ? r.nextRetryAt() : ((PersistableOutcome.Defer) outcome).nextRetryAt();

            worker.onMessage(valid(), ack);

            InOrder order = inOrder(resultService, scheduler, ack);
            order.verify(resultService).persist(claim, outcome);
            order.verify(scheduler).scheduleNotificationRetry(TENANT, NOTIFICATION, due, 3, "00-trace-span-01");
            order.verify(ack).acknowledge();
        }
    }

    @Test
    @DisplayName("A1 an existing retry trigger is not scheduled twice (scheduleNotificationRetry is not idempotent)")
    void existingTriggerIsKept() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(RETRY);
        when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(true);

        worker.onMessage(valid(), ack);

        verify(scheduler, never()).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("K9 a Quartz failure after the commit is logged and counted but never changes the result or blocks the acknowledgment")
    void quartzFailureDoesNotBlockAck() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(RETRY);
        when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(false);
        doThrow(new RuntimeException("quartz down")).when(scheduler).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());

        worker.onMessage(valid(), ack);

        verify(resultService, times(1)).persist(claim, RETRY);
        verify(ack).acknowledge();
        assertThat(meters.counter("notify.worker.quartz_failures", "channel", "email").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("K8 an acknowledgment failure is BROKER_TRANSIENT (never NON_TRANSIENT) and does not repeat the provider call here")
    void ackFailureIsBrokerTransient() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
        doThrow(new org.apache.kafka.clients.consumer.CommitFailedException()).when(ack).acknowledge();

        assertThatThrownBy(() -> worker.onMessage(valid(), ack))
                .isInstanceOf(WorkerExceptions.BrokerTransient.class)
                .satisfies(e -> assertThat(classifier.classify(e)).isEqualTo(ErrorCategory.BROKER_TRANSIENT));
        verify(orchestrator, times(1)).deliver(claim);
        verify(resultService, times(1)).persist(claim, DELIVERED);
        verify(controller, never()).onProgress(any());
    }

    @Test
    @DisplayName("A1 a database failure before the result (tenant read) is normalized and thrown; nothing is persisted or acknowledged")
    void deliverFailureIsThrownNormalized() {
        claimed();
        when(orchestrator.deliver(claim)).thenThrow(new QueryTimeoutException("slow"));

        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.Contention.class);
        verify(resultService, never()).persist(any(), any());
        verify(ack, never()).acknowledge();
    }

    // ─── persistence retry and reconciliation ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A1/6.5 transient persistence failures are retried in memory; the provider is called once and the ack happens once")
    void transientPersistFailuresAreRetried() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (calls.incrementAndGet() <= 2) {
                throw new QueryTimeoutException("slow");
            }
            return null;
        }).when(resultService).persist(claim, DELIVERED);

        worker.onMessage(valid(), ack);

        verify(resultService, times(3)).persist(claim, DELIVERED);
        verify(orchestrator, times(1)).deliver(claim);
        verify(ack, times(1)).acknowledge();
        verify(notificationService, never()).getClaimEvidence(any(), any());
    }

    @Test
    @DisplayName("A1/6.5 a non-transient persistence failure is rethrown immediately: no retry, no acknowledgment")
    void nonTransientPersistFailure() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
        doThrow(new DataIntegrityViolationException("schema")).when(resultService).persist(claim, DELIVERED);

        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.NonTransient.class);
        verify(resultService, times(1)).persist(claim, DELIVERED);
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("O5 FencedOut triggers exactly one reconcile: COMMITTED acks with post-commit steps, SUPERSEDED/ABSENT ack without")
    void fencedOutReconciles() {
        for (Object[] c : new Object[][]{
                {evidence(NotificationStatus.RETRYING, 3, 7, NOW.plusSeconds(30)), true, true},    // own committed result
                {evidence(NotificationStatus.PROCESSING, 3, 8, null), true, false},                 // superseded by claim_seq
                {null, true, false}}) {                                                              // absent
            org.mockito.Mockito.reset(resultService, notificationService, scheduler, ack, orchestrator, transitioner);
            claimed();
            when(orchestrator.deliver(claim)).thenReturn(RETRY);
            doThrow(new FencedOutException("0 rows")).when(resultService).persist(claim, RETRY);
            when(notificationService.getClaimEvidence(TENANT, NOTIFICATION)).thenReturn(Optional.ofNullable((NotificationEvidence) c[0]));
            when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(false);

            worker.onMessage(valid(), ack);

            verify(resultService, times(1)).persist(claim, RETRY);
            verify(notificationService, times(1)).getClaimEvidence(TENANT, NOTIFICATION);
            verify(ack, times(1)).acknowledge();
            verify(scheduler, (Boolean) c[2] ? times(1) : never()).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());
        }
    }

    @Test
    @DisplayName("O5 four failed persists then a final evidence read: COMMITTED, SUPERSEDED, ABSENT acknowledge; PROCESSING is UNRESOLVED and is not acknowledged")
    void exhaustedPersistThenFinalReconcile() {
        for (Object[] c : new Object[][]{
                {evidence(NotificationStatus.DELIVERED, 3, 7, null), true},
                {evidence(NotificationStatus.PROCESSING, 4, 7, null), true},
                {null, true},
                {evidence(NotificationStatus.PROCESSING, 3, 7, null), false}}) {
            org.mockito.Mockito.reset(resultService, notificationService, ack, orchestrator, transitioner);
            claimed();
            when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
            doThrow(new QueryTimeoutException("slow")).when(resultService).persist(claim, DELIVERED);
            when(notificationService.getClaimEvidence(TENANT, NOTIFICATION)).thenReturn(Optional.ofNullable((NotificationEvidence) c[0]));

            if ((Boolean) c[1]) {
                worker.onMessage(valid(), ack);
                verify(ack, times(1)).acknowledge();
            } else {
                assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.Unresolved.class);
                verify(ack, never()).acknowledge();
            }
            verify(resultService, times(4)).persist(claim, DELIVERED);
            verify(notificationService, times(1)).getClaimEvidence(TENANT, NOTIFICATION);
            verify(orchestrator, times(1)).deliver(claim);
        }
    }

    @Test
    @DisplayName("O5 if the final evidence read also fails, its classified exception is thrown and nothing is acknowledged")
    void finalReconcileReadFailure() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
        doThrow(new QueryTimeoutException("slow")).when(resultService).persist(claim, DELIVERED);
        when(notificationService.getClaimEvidence(TENANT, NOTIFICATION)).thenThrow(new RuntimeException("wrap", new SQLException("down", "08006")));

        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.Connectivity.class);
        verify(ack, never()).acknowledge();
    }

    // ─── unclaimed dispositions ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O4 row absent, terminal and stale generation: acknowledged by the worker")
    void discardedDispositionsAreAcknowledged() {
        for (NotificationEvidence ev : new NotificationEvidence[]{
                null,
                evidence(NotificationStatus.DELIVERED, 3, 1, null),
                evidence(NotificationStatus.CANCELLED, 3, 1, null),
                evidence(NotificationStatus.FAILED, 3, 1, null),
                evidence(NotificationStatus.DLQ, 3, 1, null),
                evidence(NotificationStatus.QUEUED, 5, 1, null)}) {
            org.mockito.Mockito.reset(ack, transitioner, notificationService);
            unclaimed(ev);
            worker.onMessage(valid(), ack);
            verify(ack, times(1)).acknowledge();
            verify(orchestrator, never()).deliver(any());
        }
    }

    @Test
    @DisplayName("O4 retry pending: trigger repaired from the evidence and the trace header, then acknowledged")
    void retryPendingRepairsTrigger() {
        Instant due = NOW.plusSeconds(90);
        unclaimed(evidence(NotificationStatus.RETRYING, 3, 7, due));
        when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(false);

        worker.onMessage(valid(), ack);

        verify(scheduler).scheduleNotificationRetry(TENANT, NOTIFICATION, due, 3, "00-hdr-trace-01");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("O4 retry pending with a failing scheduler is still acknowledged (non-fatal repair)")
    void retryPendingRepairFailureIsNonFatal() {
        unclaimed(evidence(NotificationStatus.RETRYING, 3, 7, NOW.plusSeconds(90)));
        when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(false);
        doThrow(new RuntimeException("quartz")).when(scheduler).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());

        worker.onMessage(valid(), ack);

        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("O4 ordering / in-progress / unexpected state throw the right typed exception and never acknowledge")
    void throwingDispositions() {
        unclaimed(evidence(NotificationStatus.QUEUED, 1, 0, null));
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).as("generation ahead").isInstanceOf(WorkerExceptions.Ordering.class);

        unclaimed(evidence(NotificationStatus.QUEUED, 3, 0, null));
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).as("claim race").isInstanceOf(WorkerExceptions.Ordering.class);

        unclaimed(evidence(NotificationStatus.PROCESSING, 3, 4, null));
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).as("in progress").isInstanceOf(WorkerExceptions.Unresolved.class);

        unclaimed(evidence(NotificationStatus.CREATED, 3, 0, null));
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).as("unexpected").isInstanceOf(WorkerExceptions.NonTransient.class);

        verify(ack, never()).acknowledge();
    }

    // ─── scope, normalization, ownership ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A1 every database call of a record runs inside the worker pool scope, and the scope is closed afterwards (also on failure)")
    void workerPoolScopeCoversTheRecordAndIsClosed() {
        AtomicBoolean claimInScope = new AtomicBoolean();
        AtomicBoolean persistInScope = new AtomicBoolean();
        AtomicBoolean deliverInScope = new AtomicBoolean();
        when(transitioner.claim(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            claimInScope.set(WorkerPoolScope.isActive());
            return new ClaimResult.Claimed(claim, NotificationStatus.QUEUED);
        });
        when(orchestrator.deliver(claim)).thenAnswer(inv -> {
            deliverInScope.set(WorkerPoolScope.isActive());
            return DELIVERED;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            persistInScope.set(WorkerPoolScope.isActive());
            return null;
        }).when(resultService).persist(claim, DELIVERED);

        worker.onMessage(valid(), ack);

        assertThat(claimInScope).isTrue();
        assertThat(deliverInScope).isTrue();
        assertThat(persistInScope).isTrue();
        assertThat(WorkerPoolScope.isActive()).isFalse();

        doThrow(new QueryTimeoutException("slow")).when(transitioner).claim(any(), any(), anyInt(), any());
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.Contention.class);
        assertThat(WorkerPoolScope.isActive()).isFalse();
    }

    @Test
    @DisplayName("A1 everything thrown by a record is a typed worker exception whose class follows the category")
    void everythingThrownIsNormalized() {
        doThrow(new RuntimeException("w", new SQLException("x", "08006"))).when(transitioner).claim(any(), any(), anyInt(), any());
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.Connectivity.class);

        doThrow(new IllegalStateException("unknown")).when(transitioner).claim(any(), any(), anyInt(), any());
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.Unclassified.class);

        doThrow(new DataIntegrityViolationException("schema")).when(transitioner).claim(any(), any(), anyInt(), any());
        assertThatThrownBy(() -> worker.onMessage(valid(), ack)).isInstanceOf(WorkerExceptions.NonTransient.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("T1 a permanent failure outcome (for example TENANT_NOT_ACTIVE) is persisted like any result and acknowledged")
    void failOutcomeIsPersistedAndAcknowledged() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(FAIL);

        worker.onMessage(valid(), ack);

        verify(resultService).persist(claim, FAIL);
        verify(ack).acknowledge();
        verify(scheduler, never()).hasRetryTrigger(any());
        assertThat(meters.counter("notify.delivery.results", "channel", "email", "outcome", "failed").count()).isEqualTo(1.0);
    }
}
