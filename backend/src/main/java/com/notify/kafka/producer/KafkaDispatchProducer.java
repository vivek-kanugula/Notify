package com.notify.kafka.producer;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Encapsulates publishing dispatch records to Kafka topics with headers:
 * - tenantId (UUID string)
 * - traceparent (W3C trace context propagated from outbox_events.trace_context)
 */
@Component
public class KafkaDispatchProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaDispatchProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final com.notify.kafka.config.KafkaTopicConfigReconciler topicReconciler;

    public KafkaDispatchProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.notify.kafka.config.KafkaTopicConfigReconciler topicReconciler
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicReconciler = topicReconciler;
    }

    public CompletableFuture<SendResult<String, String>> send(
            String topic,
            String key,
            String payload,
            String traceContext,
            UUID tenantId
    ) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);

        if (tenantId != null) {
            record.headers().add(new RecordHeader("tenantId", tenantId.toString().getBytes(StandardCharsets.UTF_8)));
        }

        if (traceContext != null && !traceContext.isBlank()) {
            record.headers().add(new RecordHeader("traceparent", traceContext.getBytes(StandardCharsets.UTF_8)));
        }

        CompletableFuture<SendResult<String, String>> future;
        try {
            future = kafkaTemplate.send(record);
        } catch (Exception ex) {
            handleMissingTopic(topic, ex);
            CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }

        return future.whenComplete((result, ex) -> {
            if (ex != null) {
                handleMissingTopic(topic, ex);
            }
        });
    }

    private void handleMissingTopic(String topic, Throwable ex) {
        if (topicReconciler == null) {
            return;
        }
        Throwable current = ex;
        boolean missingTopic = false;
        while (current != null) {
            if (current instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException) {
                missingTopic = true;
                break;
            }
            current = current.getCause();
        }
        if (missingTopic) {
            log.warn("Detected missing topic '{}' during send failure; invalidating verified topic status for re-provisioning", topic);
            topicReconciler.invalidateTopic(topic);
            topicReconciler.triggerReconciliation();
        }
    }
}
