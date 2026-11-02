package com.notify.outbox;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.outbox.service.OutboxRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxRelayTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private com.notify.tenant.repository.TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private KafkaDispatchProducer mockKafkaProducer;
    private Clock fixedClock;
    private OutboxRelayService outboxRelayService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        var tenant = new com.notify.tenant.entity.TenantEntity("RelayOrg", "relay-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();

        mockKafkaProducer = mock(KafkaDispatchProducer.class);
        fixedClock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);
        outboxRelayService = new OutboxRelayService(outboxEventRepository, mockKafkaProducer, fixedClock, transactionManager);
    }

    private OutboxEventEntity createPendingEvent(UUID tenantId, String topic, String key) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setTenantId(tenantId);
        event.setAggregateType("NOTIFICATION");
        event.setAggregateId(UUID.randomUUID());
        event.setEventType("NOTIFICATION_QUEUED");
        event.setTopic(topic);
        event.setMessageKey(key);
        event.setPayload("{\"schemaVersion\":1,\"attempt\":1}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setCreatedAt(fixedClock.instant());
        event.setNextAttemptAt(fixedClock.instant());
        return outboxEventRepository.save(event);
    }

    @Test
    @DisplayName("Happy path: OutboxRelay polls PENDING event with SKIP LOCKED, publishes, and marks PUBLISHED")
    void happyPath_publishesAndMarksPublished() {
        OutboxEventEntity event = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "key-1");

        SendResult<String, String> sendResult = mock(SendResult.class);
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        int published = outboxRelayService.processBatch();

        assertThat(published).isEqualTo(1);
        OutboxEventEntity updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isEqualTo(fixedClock.instant());
    }

    @Test
    @DisplayName("Broker failure: when Kafka publish fails, row remains PENDING and attempts counter increments")
    void brokerFailure_remainsPendingAndIncrementsAttempts() {
        OutboxEventEntity event = createPendingEvent(this.tenantId, "notify.dispatch.webhook.v1", "key-2");

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka connection refused"));
        when(mockKafkaProducer.send(any(), any(), any(), any(), any())).thenReturn(failedFuture);

        int published = outboxRelayService.processBatch();

        assertThat(published).isEqualTo(0);
        OutboxEventEntity updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(updated.getAttempts()).isEqualTo(1);
        assertThat(updated.getPublishedAt()).isNull();
        assertThat(updated.getNextAttemptAt()).isAfter(fixedClock.instant());
    }

    @Test
    @DisplayName("Concurrency: Two concurrent relays with SKIP LOCKED do not process the same locked row")
    void concurrentRelays_doNotDoubleProcessLockedRows() throws Exception {
        // Create 2 events
        OutboxEventEntity e1 = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "k1");
        OutboxEventEntity e2 = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "k2");

        CountDownLatch firstRelayStarted = new CountDownLatch(1);
        CountDownLatch allowFirstRelayToFinish = new CountDownLatch(1);

        AtomicInteger totalPublished = new AtomicInteger(0);

        // Slow mock on first call to hold row lock
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    firstRelayStarted.countDown();
                    allowFirstRelayToFinish.await(5, TimeUnit.SECONDS);
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: starts batch, acquires lock on e1 and e2, pauses during Kafka publish
        Future<Integer> f1 = executor.submit(() -> outboxRelayService.processBatch());

        firstRelayStarted.await(5, TimeUnit.SECONDS);

        // Thread 2: attempts processBatch while Thread 1 holds the lock
        // SKIP LOCKED skips the locked rows; finding 0 available rows
        Future<Integer> f2 = executor.submit(() -> outboxRelayService.processBatch());

        int count2 = f2.get(5, TimeUnit.SECONDS);
        assertThat(count2).isEqualTo(0); // Thread 2 found 0 un-locked rows!

        // Now allow Thread 1 to finish
        allowFirstRelayToFinish.countDown();
        int count1 = f1.get(5, TimeUnit.SECONDS);
        assertThat(count1).isEqualTo(2);

        executor.shutdown();

        List<OutboxEventEntity> all = outboxEventRepository.findAll();
        assertThat(all).allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Crash after broker ACK: transaction abort leaves row PENDING for safe at-least-once republication")
    void crashAfterBrokerAck_leavesPendingForRepublication() {
        OutboxEventEntity event = createPendingEvent(this.tenantId, "notify.dispatch.inapp.v1", "k-crash");

        // Mock broker ACK
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        // Simulate relay publishing to broker, receiving ACK, but transaction aborts before marking published in DB
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        try {
            tt.execute(status -> {
                List<OutboxEventEntity> batch = outboxEventRepository.findPendingFreshForUpdateSkipLocked(10);
                assertThat(batch).hasSize(1);
                OutboxEventEntity item = batch.get(0);

                // Act: Relay sends and receives broker ACK
                try {
                    mockKafkaProducer.send(
                            item.getTopic(),
                            item.getMessageKey(),
                            item.getPayload(),
                            item.getTraceContext(),
                            item.getTenantId()
                    ).get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                // Simulate post-ACK crash before updateStatusAndPublishedAt is committed
                throw new RuntimeException("Simulated JVM crash / DB connection loss after Kafka ACK");
            });
        } catch (RuntimeException expected) {
            // Expected simulation
        }

        // Row was NOT committed as PUBLISHED: it remains PENDING
        OutboxEventEntity surviving = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(surviving.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // On next relay run, it is picked up and published (at-least-once republication)
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        int republishedCount = outboxRelayService.processBatch();
        assertThat(republishedCount).isEqualTo(1);

        OutboxEventEntity finalState = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Head-of-line starvation: Failing first outbox row does not block subsequent healthy pending rows from publishing")
    void headOfLineStarvation_failingRowDoesNotBlockHealthyRows() {
        // Arrange: Row 1 fails permanently; Row 2 and Row 3 are healthy
        OutboxEventEntity failingRow = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "fail-key");
        OutboxEventEntity healthyRow2 = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "healthy-key-2");
        OutboxEventEntity healthyRow3 = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "healthy-key-3");

        // Mock producer: throw on "fail-key", succeed on others
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    if ("fail-key".equals(key)) {
                        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
                        failed.completeExceptionally(new RuntimeException("Kafka permanent failure on fail-key"));
                        return failed;
                    }
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                });

        // Act: processBatch
        int published = outboxRelayService.processBatch();

        // Assert: 2 healthy events published within the same batch!
        assertThat(published).isEqualTo(2);

        // Healthy rows are PUBLISHED
        OutboxEventEntity h2 = outboxEventRepository.findById(healthyRow2.getId()).orElseThrow();
        assertThat(h2.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(h2.getPublishedAt()).isNotNull();

        OutboxEventEntity h3 = outboxEventRepository.findById(healthyRow3.getId()).orElseThrow();
        assertThat(h3.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(h3.getPublishedAt()).isNotNull();

        // Failing row remains durable, retryable, observable (attempts incremented to 1)
        OutboxEventEntity f1 = outboxEventRepository.findById(failingRow.getId()).orElseThrow();
        assertThat(f1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(f1.getAttempts()).isEqualTo(1);
        assertThat(f1.getPublishedAt()).isNull();

        // Now arrange a new healthy event created later (attempts = 0)
        OutboxEventEntity healthyRow4 = createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "healthy-key-4");

        // Act: second batch run with batchSize=1.
        // Query orders by attempts ASC, so healthyRow4 (attempts=0) is selected ahead of failingRow (attempts=1).
        outboxRelayService.setBatchSize(1);
        int publishedBatch2 = outboxRelayService.processBatch();
        assertThat(publishedBatch2).isEqualTo(1);

        OutboxEventEntity h4 = outboxEventRepository.findById(healthyRow4.getId()).orElseThrow();
        assertThat(h4.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        // failingRow was NOT picked in batch 2 because healthyRow4 took the slot; its attempts is still 1
        OutboxEventEntity f1BeforeRetry = outboxEventRepository.findById(failingRow.getId()).orElseThrow();
        assertThat(f1BeforeRetry.getAttempts()).isEqualTo(1);

        // When no attempts=0 rows remain and clock advances past backoff, failingRow is retried
        outboxRelayService.setClock(Clock.fixed(fixedClock.instant().plusSeconds(5), ZoneOffset.UTC));
        outboxRelayService.processBatch();
        OutboxEventEntity f1AfterRetry = outboxEventRepository.findById(failingRow.getId()).orElseThrow();
        assertThat(f1AfterRetry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(f1AfterRetry.getAttempts()).isEqualTo(2); // Increment to 2 upon retry
    }

    @Test
    @DisplayName("Dead broker: 10-row batch persists attempts=1 and exponential backoff within transaction budget when Kafka is refused")
    void deadBroker_10RowBatchPersistsAttemptsAndBackoffWithinHeadroom() {
        // Arrange: 10 pending outbox events
        for (int i = 0; i < 10; i++) {
            createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "dead-key-" + i);
        }

        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException("Connection refused"));
                    return failed;
                });

        long start = System.currentTimeMillis();
        int published = outboxRelayService.processBatch();
        long elapsed = System.currentTimeMillis() - start;

        // Bounded completion: well under the 15s transaction timeout
        assertThat(elapsed).isLessThan(5000L);
        assertThat(published).isEqualTo(0);

        List<OutboxEventEntity> all = outboxEventRepository.findAll();
        assertThat(all).hasSize(10);
        assertThat(all).allMatch(e -> e.getStatus() == OutboxStatus.PENDING);
        assertThat(all).allMatch(e -> e.getAttempts() == 1);
        assertThat(all).allMatch(e -> e.getNextAttemptAt() != null && e.getNextAttemptAt().isAfter(fixedClock.instant()));
    }

    @Test
    @DisplayName("Black-holed broker: 10-row batch bounded by per-message timeout persists attempts and backoff without transaction rollback")
    void blackHoledBroker_10RowBatchBoundedByTimeout_persistsAttemptsAndBackoff() {
        for (int i = 0; i < 10; i++) {
            createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "blackhole-key-" + i);
        }

        // Configure short per-message timeout for test speed (50ms per message -> 500ms total budget)
        outboxRelayService.setPerMessageTimeoutMs(50L);

        // Mock producer: future that hangs (simulating network black-hole)
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new CompletableFuture<>());

        long start = System.currentTimeMillis();
        int published = outboxRelayService.processBatch();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(5000L);
        assertThat(published).isEqualTo(0);

        List<OutboxEventEntity> all = outboxEventRepository.findAll();
        assertThat(all).hasSize(10);
        // All 10 rows must have attempts=1 and future nextAttemptAt committed to PostgreSQL (not rolled back!)
        assertThat(all).allMatch(e -> e.getStatus() == OutboxStatus.PENDING);
        assertThat(all).allMatch(e -> e.getAttempts() == 1);
        assertThat(all).allMatch(e -> e.getNextAttemptAt() != null && e.getNextAttemptAt().isAfter(fixedClock.instant()));
    }

    @Test
    @DisplayName("Slow working broker: messages progress and are marked PUBLISHED within per-message timeout")
    void slowWorkingBroker_messagesProgressAndMarkPublished() {
        for (int i = 0; i < 5; i++) {
            createPendingEvent(this.tenantId, "notify.dispatch.email.v1", "slow-key-" + i);
        }

        // Mock producer: slow ACK (e.g. 30ms latency) well within 1000ms per-message timeout
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignored) {}
                    return mock(SendResult.class);
                }));

        int published = outboxRelayService.processBatch();

        assertThat(published).isEqualTo(5);
        List<OutboxEventEntity> all = outboxEventRepository.findAll();
        assertThat(all).hasSize(5);
        assertThat(all).allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED);
        assertThat(all).allMatch(e -> e.getPublishedAt() != null);
    }
}
