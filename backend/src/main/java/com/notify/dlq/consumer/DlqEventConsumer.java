package com.notify.dlq.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.dto.DeadLetterEventEnvelope.UnparsableDeadLetterEventException;
import com.notify.dlq.model.MaterializationOutcome;
import com.notify.dlq.service.DeadLetterMaterializationService;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.dlq.service.QuarantineService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes the authoritative {@code notify.dlq.v1} events (/ , ).
 *
 * <p><b>This is the only path that may materialize a business dead-letter entry</b> (). The event is written
 * in the same transaction as the FAILED status, so it is the committed verdict - not an observation about it.
 *
 * <p><b>Acknowledgment order.</b> The materialization transaction commits first, then the offset is acknowledged.
 * A failure before commit leaves the record unacknowledged, so a redelivery classifies it again from scratch and
 * the outcome matrix makes that redelivery a proven duplicate rather than a second effect.
 *
 * <p>An unparsable record is quarantined and then acknowledged: there is deliberately no dead-letter topic for the
 * dead-letter topic, and a record that has been recorded durably does not need to come back.
 *
 * <p>Not component-scanned: the bean is declared by {@link DlqConsumerKafkaConfig}, which is conditional on
 * {@code notify.dlq.consumer.enabled}. With the consumer disabled - the default - no listener, container,
 * consumer or controller exists at all (the discipline).
 */
public class DlqEventConsumer {

    public static final String LISTENER_ID = "notify-dlq-event-consumer";

    private static final Logger log = LoggerFactory.getLogger(DlqEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeadLetterMaterializationService materialization;
    private final QuarantineService quarantine;
    private final DlqConsumerContainerController controller;
    private final MeterRegistry meters;

    public DlqEventConsumer(
            DeadLetterMaterializationService materialization,
            QuarantineService quarantine,
            DlqConsumerContainerController controller,
            MeterRegistry meters
    ) {
        this.materialization = materialization;
        this.quarantine = quarantine;
        this.controller = controller;
        this.meters = meters;
    }

    @KafkaListener(
            id = LISTENER_ID,
            idIsGroup = false,
            // The contracted topic is the default. It is overridable for the same reason every delivery worker's
            // topic is: an acceptance run must be able to own a topic of its own rather than share the contracted
            // one with another suite on the same broker.
            topics = "${notify.dlq.consumer.topic:notify.dlq.v1}",
            groupId = "${notify.dlq.consumer.group-id:notify-dlq-consumer}",
            containerFactory = DlqConsumerKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "false"
    )
    public void onDeadLetterEvent(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        RecordCoordinates coordinates = new RecordCoordinates(
                record.topic(), record.partition(), record.offset(), record.value());

        DeadLetterEventEnvelope event;
        try {
            JsonNode node = MAPPER.readTree(record.value());
            event = DeadLetterEventEnvelope.parse(node);
        } catch (UnparsableDeadLetterEventException | java.io.IOException e) {
            // Recorded before it is acknowledged: nothing is dropped silently.
            quarantine.recordUnparsable(coordinates, "DLQ_EVENT_UNPARSABLE", e.getClass().getName());
            count("unparsable");
            log.warn("Unparsable record on {}-{}@{} quarantined ({})",
                    record.topic(), record.partition(), record.offset(), e.getClass().getName());
            acknowledgment.acknowledge();
            return;
        }

        MaterializationOutcome outcome = materialization.apply(event, coordinates);
        count(outcome.name().toLowerCase(java.util.Locale.ROOT));
        controller.onSuccess(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()));
        // Only after the transaction above committed.
        acknowledgment.acknowledge();
        log.debug("Dead-letter event {} for notification {}: {}",
                event.eventId(), event.notificationId(), outcome);
    }

    private void count(String outcome) {
        if (meters != null) {
            meters.counter("notify.dlq.materialization", "outcome", outcome).increment();
        }
    }
}
