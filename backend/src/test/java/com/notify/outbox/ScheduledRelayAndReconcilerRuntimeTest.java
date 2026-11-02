package com.notify.outbox;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "notify.outbox.relay-enabled=true",
        "notify.outbox.relay-interval-ms=100",
        "notify.reconciler.enabled=true",
        "notify.reconciler.interval-ms=100",
        "notify.reconciler.grace-period-seconds=0"
})
// / -3: close this context when the class finishes. Spring caches test contexts for the whole JVM
// run, so the 100 ms relay and reconciler enabled above keep ticking long after this class is done - and the
// outbox relay's fetch is global, with no tenant predicate. That background relay was attempting rows other
// suites had seeded and expected to attempt themselves (observed: two of KafkaDownStartupAndIngressTest's ten
// rows committed attempts=2 instead of 1). Dirtying the context stops the schedulers here instead of weakening
// anyone's assertion.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledRelayAndReconcilerRuntimeTest extends AbstractPostgresIntegrationTest {

    @MockitoBean
    private KafkaDispatchProducer kafkaDispatchProducer;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        TenantEntity tenant = new TenantEntity("ScheduledRuntimeOrg", "scheduled-runtime-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();
    }

    @Test
    @DisplayName("Scheduled relay: background @Scheduled timer automatically polls and publishes PENDING outbox row without calling processBatch()")
    void scheduledRelay_automaticallyPublishesPendingRow() {
        // Arrange mock Kafka send ACK
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaDispatchProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        // Insert a PENDING outbox event into DB
        OutboxEventEntity event = new OutboxEventEntity();
        event.setTenantId(tenantId);
        event.setAggregateType("NOTIFICATION");
        event.setAggregateId(UUID.randomUUID());
        event.setEventType("NOTIFICATION_QUEUED");
        event.setTopic("notify.dispatch.email.v1");
        event.setMessageKey("sched-key-1");
        event.setPayload("{\"schemaVersion\":1,\"attempt\":1}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setCreatedAt(Instant.now());
        event.setNextAttemptAt(event.getCreatedAt());
        final OutboxEventEntity saved = outboxEventRepository.save(event);

        // DO NOT call processBatch()! Assert background timer automatically processes it
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEventEntity updated = outboxEventRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(updated.getPublishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("Scheduled reconciler: background @Scheduled timer automatically recovers overdue SCHEDULED notification without calling reconcileAll()")
    void scheduledReconciler_automaticallyReconcilesOverdueNotification() {
        // Arrange mock Kafka send ACK for outbox relay
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaDispatchProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        // Insert an overdue SCHEDULED notification (scheduled in the past)
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.SCHEDULED);
        n.setRecipient("reconciler-runtime@example.com");
        n.setSubject("Reconciler Subject");
        n.setBody("Reconciler Body");
        n.setDispatchGeneration(0);
        n.setScheduledAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        final NotificationEntity saved = notificationRepository.save(n);

        // DO NOT call reconcileAll()! Assert background timer automatically picks it up
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            NotificationEntity updated = notificationRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);
            assertThat(updated.getDispatchGeneration()).isEqualTo(1);
        });
    }
}
