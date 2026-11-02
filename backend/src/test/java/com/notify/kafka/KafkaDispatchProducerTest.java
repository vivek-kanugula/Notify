package com.notify.kafka;

import com.notify.kafka.config.KafkaTopicConfigReconciler;
import com.notify.kafka.producer.KafkaDispatchProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaDispatchProducerTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private KafkaTopicConfigReconciler topicReconciler;
    private KafkaDispatchProducer producer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        topicReconciler = mock(KafkaTopicConfigReconciler.class);
        producer = new KafkaDispatchProducer(kafkaTemplate, topicReconciler);
    }

    @Test
    @DisplayName("TimeoutException does NOT invalidate topic or trigger reconciliation (F1)")
    void timeoutException_doesNotInvalidateTopicOrTriggerReconciliation() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("Kafka broker request timed out"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        CompletableFuture<SendResult<String, String>> future = producer.send(
                "notify.dispatch.email.v1",
                "key-1",
                "payload-1",
                null,
                UUID.randomUUID()
        );

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        verify(topicReconciler, never()).invalidateTopic(anyString());
        verify(topicReconciler, never()).triggerReconciliation();
    }

    @Test
    @DisplayName("UnknownTopicOrPartitionException invalidates topic and triggers coalesced reconciliation (F1)")
    void unknownTopicOrPartitionException_invalidatesTopicAndTriggersReconciliation() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new UnknownTopicOrPartitionException("Topic deleted or not found"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        CompletableFuture<SendResult<String, String>> future = producer.send(
                "notify.dispatch.email.v1",
                "key-2",
                "payload-2",
                null,
                UUID.randomUUID()
        );

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(UnknownTopicOrPartitionException.class);

        verify(topicReconciler, times(1)).invalidateTopic("notify.dispatch.email.v1");
        verify(topicReconciler, times(1)).triggerReconciliation();
    }

    @Test
    @DisplayName("Headers propagation: tenantId and traceparent headers correctly attached to ProducerRecord")
    @SuppressWarnings("unchecked")
    void recordHeaders_propagatesTenantIdAndTraceparent() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        UUID tenantId = UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        producer.send("notify.dispatch.email.v1", "key-3", "payload-3", traceparent, tenantId);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("notify.dispatch.email.v1");
        assertThat(record.key()).isEqualTo("key-3");
        assertThat(record.value()).isEqualTo("payload-3");

        var tenantHeader = record.headers().lastHeader("tenantId");
        assertThat(tenantHeader).isNotNull();
        assertThat(new String(tenantHeader.value(), StandardCharsets.UTF_8)).isEqualTo(tenantId.toString());

        var traceHeader = record.headers().lastHeader("traceparent");
        assertThat(traceHeader).isNotNull();
        assertThat(new String(traceHeader.value(), StandardCharsets.UTF_8)).isEqualTo(traceparent);
    }
}
