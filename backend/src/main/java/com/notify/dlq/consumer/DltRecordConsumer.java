package com.notify.dlq.consumer;

import com.notify.delivery.worker.config.WorkerRecoverySupport;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.dlq.service.DltAssociationResolver;
import com.notify.dlq.service.DltAssociationResolver.Resolution;
import com.notify.dlq.service.QuarantineService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;

/**
 * Consumes the per-channel dead-letter topics (/ , , ).
 *
 * <p><b>It performs no lifecycle mutation, direct or indirect.</b> No {@code notifications} write, no
 * {@code notification_status_history} row, no {@code dead_letter_entries} row, no outbox event, no
 * {@code replay_count} change. A dead-lettered dispatch record is an observation that a worker could not process a
 * record - not a business outcome - so it can neither create a replacement business dead-letter event nor overwrite
 * the authoritative failure reason that the committed delivery or reaper result established.
 *
 * <p><b>Every record is quarantined, including a verified association.</b> A matching notification and generation
 * prove the record refers to that dispatch; they do not prove it caused the failure. That is why
 * {@code DlqReason.POISON_MESSAGE} gains no producer in it stays supported and reserved for a future producer
 * that can establish causation inside a committed result transaction.
 *
 * <p><b>A delayed record is harmless by construction.</b> `UNRESOLVED_PROCESSING_STUCK` records can arrive long
 * after the reaper rescheduled the work, or after it was delivered, replayed or cancelled; since no status is
 * written here, none of those can be terminalized by a stale observation.
 *
 * <p>The row is committed before the offset is acknowledged, so a poison record is never acknowledged unrecorded.
 */
public class DltRecordConsumer {

    public static final String LISTENER_ID = "notify-dlt-record-consumer";

    private static final Logger log = LoggerFactory.getLogger(DltRecordConsumer.class);

    private final DltAssociationResolver resolver;
    private final QuarantineService quarantine;
    private final DlqConsumerContainerController controller;
    private final MeterRegistry meters;

    public DltRecordConsumer(
            DltAssociationResolver resolver,
            QuarantineService quarantine,
            DlqConsumerContainerController controller,
            MeterRegistry meters
    ) {
        this.resolver = resolver;
        this.quarantine = quarantine;
        this.controller = controller;
        this.meters = meters;
    }

    @KafkaListener(
            id = LISTENER_ID,
            idIsGroup = false,
            topics = {
                    "#{T(com.notify.kafka.config.KafkaTopicsConfig).TOPIC_DISPATCH_EMAIL_DLT}",
                    "#{T(com.notify.kafka.config.KafkaTopicsConfig).TOPIC_DISPATCH_WEBHOOK_DLT}",
                    "#{T(com.notify.kafka.config.KafkaTopicsConfig).TOPIC_DISPATCH_INAPP_DLT}"
            },
            groupId = "${notify.dlq.dlt-consumer.group-id:notify-dlt-consumer}",
            containerFactory = DltConsumerKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "false"
    )
    public void onDltRecord(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        RecordCoordinates coordinates = new RecordCoordinates(
                record.topic(), record.partition(), record.offset(), record.value());
        String dltReason = header(record, WorkerRecoverySupport.DLT_REASON_HEADER, "UNSPECIFIED");
        String category = header(record, WorkerRecoverySupport.CATEGORY_HEADER, null);
        String exceptionClass = exceptionHeader(record);

        Resolution resolution = resolver.resolve(record.value());
        quarantine.recordDltRecord(
                coordinates,
                dltReason,
                category,
                exceptionClass,
                resolution.association(),
                resolution.claimedTenantId(),
                resolution.claimedNotificationId(),
                resolution.claimedGeneration(),
                resolution.verifiedTenantId(),
                resolution.verifiedNotificationId());

        if (meters != null) {
            meters.counter("notify.dlq.quarantined",
                    "reason", dltReason, "association", resolution.association().name()).increment();
        }
        controller.onSuccess(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()));
        // Only after the quarantine row committed. Nothing about the notification was touched.
        acknowledgment.acknowledge();
        log.warn("Quarantined dead-lettered dispatch record {}-{}@{} (reason={}, association={})",
                record.topic(), record.partition(), record.offset(), dltReason, resolution.association());
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name, String fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null
                ? fallback : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * The exception class name spring-kafka recorded, if any. Only a class name is ever read: the DLT headers
     * carry no message, no stack and no record body by construction (wiring).
     */
    private static String exceptionHeader(ConsumerRecord<String, byte[]> record) {
        for (Header header : record.headers()) {
            if (header.key() != null && header.key().toLowerCase(java.util.Locale.ROOT).contains("exception-fqcn")) {
                return header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
