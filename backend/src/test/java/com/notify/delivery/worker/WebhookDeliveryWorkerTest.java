package com.notify.delivery.worker;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.ClaimDispositionClassifier;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.delivery.service.ReconciliationEvaluator;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions;
import com.notify.notification.dto.ClaimResult;
import com.notify.notification.dto.ClaimSnapshot;
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
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the webhook worker adds on top of {@link AbstractDeliveryWorker}: its own envelope validation, its own topic
 * and listener, and a null sender snapshot. The shared flow itself — fencing, reconciliation, acknowledgment
 * ownership, post-commit repair — is covered once, by {@code EmailDeliveryWorkerTest}, against the same base class.
 */
class WebhookDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NOTIFICATION = UUID.randomUUID();
    private static final String TOPIC = "notify.dispatch.webhook.v1";

    private final NotificationTransitioner transitioner = mock(NotificationTransitioner.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final DeliveryOrchestrator orchestrator = mock(DeliveryOrchestrator.class);
    private final DeliveryResultService resultService = mock(DeliveryResultService.class);
    private final NotificationSchedulerService scheduler = mock(NotificationSchedulerService.class);
    private final WorkerContainerController controller = mock(WorkerContainerController.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private WebhookDeliveryWorker worker;

    private final ClaimSnapshot claim = new ClaimSnapshot(NOTIFICATION, TENANT, 3, 7, 1, 2, 5, 0, Channel.WEBHOOK,
            "https://receiver.example.com/hook", null, "body", null, "00-trace-span-01", null,
            Instant.parse("2026-10-13T11:00:00Z"), Instant.parse("2026-10-13T10:00:00Z"));

    private static final PersistableOutcome DELIVERED = new PersistableOutcome.Delivered(NOW, NOW, "webhook", null);
    private static final PersistableOutcome RETRY = new PersistableOutcome.Retry(NOW, NOW, "webhook",
            "WEBHOOK_SERVER_ERROR", "Receiver answered 503", 503, null, NOW.plusSeconds(30));

    @BeforeEach
    void setUp() {
        worker = new WebhookDeliveryWorker(transitioner, notificationService, orchestrator, resultService,
                new ClaimDispositionClassifier(), new ReconciliationEvaluator(), new DeliveryExceptionClassifier(),
                scheduler, controller, Clock.fixed(NOW, ZoneOffset.UTC), meters, new long[]{0, 0, 0});
    }

    private static String payload(String eventType, String channel, int generation) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + eventType
                + "\",\"notificationId\":\"" + NOTIFICATION + "\",\"tenantId\":\"" + TENANT + "\",\"channel\":\"" + channel
                + "\",\"generation\":" + generation + ",\"attempt\":1,\"occurredAt\":\"2026-10-13T12:00:00Z\"}";
    }

    private static ConsumerRecord<String, byte[]> record(String json) {
        ConsumerRecord<String, byte[]> r = new ConsumerRecord<>(TOPIC, 1, 42L,
                NOTIFICATION.toString(), json == null ? null : json.getBytes(StandardCharsets.UTF_8));
        r.headers().add(new RecordHeader("traceparent", "00-hdr-trace-01".getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    private static ConsumerRecord<String, byte[]> valid() {
        return record(payload("NOTIFICATION_QUEUED", "WEBHOOK", 3));
    }

    private void claimed() {
        when(transitioner.claim(eq(TENANT), eq(NOTIFICATION), eq(3), any()))
                .thenReturn(new ClaimResult.Claimed(claim, NotificationStatus.QUEUED));
    }

    @Test
    @DisplayName("malformed or contract-violating payloads are POISON, are never claimed and are never acknowledged")
    void poisonPayloads() {
        List<String> bad = new ArrayList<>(List.of(
                "", "not json", "{}", "[]", "null", "{\"schemaVersion\":1}",
                payload("NOTIFICATION_QUEUED", "EMAIL", 3),
                payload("NOTIFICATION_QUEUED", "WEBHOOK", -1),
                payload("SOMETHING_ELSE", "WEBHOOK", 3),
                payload("NOTIFICATION_QUEUED", "WEBHOOK", 3).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                payload("NOTIFICATION_QUEUED", "WEBHOOK", 3).replace(NOTIFICATION.toString(), "not-a-uuid"),
                payload("NOTIFICATION_QUEUED", "WEBHOOK", 3).replace("\"generation\":3,", "")));

        for (String json : bad) {
            assertThatThrownBy(() -> worker.onMessage(record(json), ack))
                    .as(json).isInstanceOf(WorkerExceptions.Poison.class);
        }
        assertThatThrownBy(() -> worker.onMessage(record(null), ack)).isInstanceOf(WorkerExceptions.Poison.class);
        verifyNoInteractions(transitioner, orchestrator, resultService, ack);
    }

    @Test
    @DisplayName("an EMAIL envelope on the webhook topic is poison, so a misrouted record cannot be delivered as a webhook")
    void misroutedChannelIsPoison() {
        assertThatThrownBy(() -> worker.onMessage(record(payload("NOTIFICATION_QUEUED", "EMAIL", 3)), ack))
                .isInstanceOf(WorkerExceptions.Poison.class);

        verifyNoInteractions(orchestrator, resultService, ack);
    }

    @Test
    @DisplayName("unknown extra fields are ignored and all three event types are accepted")
    void validEnvelopes() {
        for (String type : List.of("NOTIFICATION_QUEUED", "NOTIFICATION_RETRY_DUE", "NOTIFICATION_REPLAYED")) {
            claimed();
            when(orchestrator.deliver(claim)).thenReturn(DELIVERED);
            worker.onMessage(record(payload(type, "WEBHOOK", 3).replace("}", ",\"futureField\":\"x\"}")), ack);
        }

        verify(ack, times(3)).acknowledge();
    }

    @Test
    @DisplayName("the claim offers a null sender: the sender snapshot () is an email concept")
    void senderStaysNull() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);

        worker.onMessage(valid(), ack);

        verify(transitioner).claim(TENANT, NOTIFICATION, 3, null);
    }

    @Test
    @DisplayName("claimed: deliver once, persist, then acknowledge once, in that order, and report progress")
    void happyPathOrder() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(DELIVERED);

        worker.onMessage(valid(), ack);

        InOrder order = inOrder(transitioner, orchestrator, resultService, ack, controller);
        order.verify(transitioner).claim(TENANT, NOTIFICATION, 3, null);
        order.verify(orchestrator).deliver(claim);
        order.verify(resultService).persist(claim, DELIVERED);
        order.verify(ack).acknowledge();
        order.verify(controller).onProgress(new TopicPartition(TOPIC, 1));
        verify(scheduler, never()).scheduleNotificationRetry(any(), any(), any(), anyInt(), any());
        assertThat(meters.counter("notify.delivery.results", "channel", "webhook", "outcome", "delivered").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a retryable webhook failure schedules the Quartz trigger after the commit, then acknowledges")
    void retrySchedulesTriggerAfterCommit() {
        claimed();
        when(orchestrator.deliver(claim)).thenReturn(RETRY);
        when(scheduler.hasRetryTrigger(NOTIFICATION)).thenReturn(false);

        worker.onMessage(valid(), ack);

        InOrder order = inOrder(resultService, scheduler, ack);
        order.verify(resultService).persist(claim, RETRY);
        order.verify(scheduler).scheduleNotificationRetry(
                TENANT, NOTIFICATION, NOW.plusSeconds(30), 3, "00-trace-span-01");
        order.verify(ack).acknowledge();
    }

    @Test
    @DisplayName("the parsed envelope keeps its identifiers, so the claim is fenced on the event's own generation")
    void envelopeIsParsedFaithfully() {
        var event = WebhookDeliveryWorker.parse(
                payload("NOTIFICATION_RETRY_DUE", "WEBHOOK", 9).getBytes(StandardCharsets.UTF_8));

        assertThat(event.notificationId()).isEqualTo(NOTIFICATION);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.generation()).isEqualTo(9);
        assertThat(event.channel()).isEqualTo("WEBHOOK");
        assertThat(event.eventType()).isEqualTo("NOTIFICATION_RETRY_DUE");
    }

    @Test
    @DisplayName("a generation of zero is accepted: only a negative generation is a contract violation")
    void zeroGenerationIsValid() {
        assertThat(WebhookDeliveryWorker.parse(
                payload("NOTIFICATION_QUEUED", "WEBHOOK", 0).getBytes(StandardCharsets.UTF_8)).generation())
                .isZero();
    }
}
