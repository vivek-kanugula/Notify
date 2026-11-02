package com.notify.outbox;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.outbox.service.OutboxRelayService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * / the scheduled relay tick drains consecutive full batches instead of publishing one batch and
 * then idling for the fixed delay. This is the behavioural proof of the throughput change — the measured
 * rate itself is advisory and recorded in docs/build/evidence/N23A_PERFORMANCE_REPORT.md (/).
 *
 * <p>Per-batch invariants are untouched (): batch cap 10, per-message timeout 1 000 ms, 15 s transaction
 * timeout, 3 000 ms headroom. Each drained cycle remains its own transaction.
 */
class OutboxRelayDrainLoopTest extends AbstractPostgresIntegrationTest {

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private OutboxRelayService relay;
    private Clock fixedClock;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        TenantEntity tenant = tenantRepository.save(
                new TenantEntity("DrainOrg", "drain-org-" + UUID.randomUUID()));
        this.tenantId = tenant.getId();

        this.fixedClock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);

        KafkaDispatchProducer producer = mock(KafkaDispatchProducer.class);
        @SuppressWarnings("unchecked")
        SendResult<String, String> ack = mock(SendResult.class);
        when(producer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ack));

        // Constructed directly, so the @Value defaults apply: batch-size 10, max-drain-cycles 20.
        this.relay = new OutboxRelayService(outboxEventRepository, producer, fixedClock, transactionManager);
    }

    private void seedPending(int count) {
        for (int i = 0; i < count; i++) {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setTenantId(tenantId);
            event.setAggregateType("NOTIFICATION");
            event.setAggregateId(UUID.randomUUID());
            event.setEventType("NOTIFICATION_QUEUED");
            event.setTopic("notify.dispatch.email.v1");
            event.setMessageKey(UUID.randomUUID().toString());
            event.setPayload("{\"schemaVersion\":1,\"attempt\":1}");
            event.setStatus(OutboxStatus.PENDING);
            event.setAttempts(0);
            event.setCreatedAt(fixedClock.instant());
            event.setNextAttemptAt(fixedClock.instant());
            outboxEventRepository.save(event);
        }
    }

    @Test
    @DisplayName("one tick drains a backlog larger than a single batch")
    void oneTickDrainsMultipleBatches() {
        seedPending(25); // 3 cycles at the batch cap of 10: 10 + 10 + 5

        int published = relay.relayPendingEvents();

        assertThat(published).isEqualTo(25);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(25);
    }

    @Test
    @DisplayName("a partial batch ends the tick instead of spinning on an empty backlog")
    void partialBatchStopsTheTick() {
        seedPending(4); // fewer than the batch cap, so exactly one cycle runs

        int published = relay.relayPendingEvents();

        assertThat(published).isEqualTo(4);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
    }

    @Test
    @DisplayName("an empty backlog publishes nothing and does not fail")
    void emptyBacklogIsANoop() {
        assertThat(relay.relayPendingEvents()).isZero();
    }

    @Test
    @DisplayName("the send-budget guard still rejects a batch that would breach transaction headroom")
    void sendBudgetGuardUnchanged() {
        // 15 s timeout - 3 000 ms headroom = 12 000 ms budget; 10 x 1 000 ms fits, 10 x 2 000 ms does not.
        OutboxRelayService.validateTimeoutConfiguration(10, 1000L, 15);

        assertThat(
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> OutboxRelayService.validateTimeoutConfiguration(10, 2000L, 15)))
                .isInstanceOf(IllegalStateException.class);
    }
}
