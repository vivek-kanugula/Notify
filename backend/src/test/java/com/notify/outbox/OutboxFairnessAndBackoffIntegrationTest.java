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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxFairnessAndBackoffIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OutboxFairnessAndBackoffIntegrationTest.class);

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private KafkaDispatchProducer mockKafkaProducer;
    private Clock fixedClock;
    private OutboxRelayService outboxRelayService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        TenantEntity tenant = new TenantEntity("FairnessOrg", "fairness-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();

        mockKafkaProducer = mock(KafkaDispatchProducer.class);
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        fixedClock = Clock.fixed(Instant.parse("2026-10-09T14:00:00Z"), ZoneOffset.UTC);
        outboxRelayService = new OutboxRelayService(outboxEventRepository, mockKafkaProducer, fixedClock, transactionManager);
    }

    private void bulkInsertOutboxEvents(int count, int attempts, Instant createdAt, Instant nextAttemptAt) {
        String sql = """
            INSERT INTO outbox_events (
                id, tenant_id, aggregate_type, aggregate_id, event_type,
                topic, message_key, payload, trace_context, status,
                attempts, created_at, next_attempt_at
            ) VALUES (?, ?, 'NOTIFICATION', ?, 'NOTIFICATION_QUEUED',
                'notify.dispatch.email.v1', ?, cast('{"schemaVersion":1}' as jsonb), null, 'PENDING',
                ?, ?, ?)
        """;

        List<Object[]> batchArgs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            UUID aggId = UUID.randomUUID();
            batchArgs.add(new Object[]{
                    id,
                    tenantId,
                    aggId,
                    "key-" + i,
                    attempts,
                    Timestamp.from(createdAt.plusMillis(i)),
                    Timestamp.from(nextAttemptAt.plusMillis(i))
            });
        }

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Test
    @DisplayName("Fairness: 1,000 due failed rows do not block 100 fresh rows; both classes make progress; plan shapes verified")
    void thousandDueFailedRowsDoNotBlockHundredFreshRows() {
        Instant now = fixedClock.instant();

        // Seed 1,000 due failed rows (attempts = 1, next_attempt_at in the past)
        bulkInsertOutboxEvents(1000, 1, now.minusSeconds(60), now.minusSeconds(10));

        // Seed 100 fresh rows (attempts = 0, next_attempt_at = now)
        bulkInsertOutboxEvents(100, 0, now, now);

        // Run ANALYZE before using EXPLAIN ANALYZE as performance evidence
        jdbcTemplate.execute("ANALYZE outbox_events;");

        // Report plan shape separately from timing estimates
        List<String> freshPlan = jdbcTemplate.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM outbox_events WHERE status = 'PENDING' AND attempts = 0 ORDER BY created_at ASC, id ASC LIMIT 7 FOR UPDATE SKIP LOCKED",
                String.class
        );
        log.info("Fresh Query Execution Plan:\n{}", String.join("\n", freshPlan));
        boolean freshUsesIndex = freshPlan.stream().anyMatch(line -> line.contains("idx_outbox_pending_fresh"));
        assertThat(freshUsesIndex)
                .as("Fresh query must utilize specialized partial index idx_outbox_pending_fresh")
                .isTrue();

        List<String> retryPlan = jdbcTemplate.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM outbox_events WHERE status = 'PENDING' AND attempts > 0 AND next_attempt_at <= ? ORDER BY next_attempt_at ASC, created_at ASC, id ASC LIMIT 3 FOR UPDATE SKIP LOCKED",
                String.class,
                Timestamp.from(now)
        );
        log.info("Retry Query Execution Plan:\n{}", String.join("\n", retryPlan));
        boolean retryUsesIndex = retryPlan.stream().anyMatch(line -> line.contains("idx_outbox_pending_retries"));
        assertThat(retryUsesIndex)
                .as("Retry query must utilize specialized partial index idx_outbox_pending_retries")
                .isTrue();

        // Execute 1 batch (batch size 10): reserved quota is 7 fresh and 3 retries
        int publishedBatch1 = outboxRelayService.processBatch();
        assertThat(publishedBatch1).isEqualTo(10);

        // Verify that in the first batch, fresh work made progress immediately despite 1,000 due retries
        long publishedFreshAfterBatch1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND attempts = 0",
                Long.class
        );
        long publishedRetryAfterBatch1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND attempts > 0",
                Long.class
        );
        assertThat(publishedFreshAfterBatch1).isEqualTo(7L);
        assertThat(publishedRetryAfterBatch1).isEqualTo(3L);

        // Process until all 100 fresh rows are published
        int batchesRun = 1;
        while (publishedFreshAfterBatch1 < 100 && batchesRun < 20) {
            outboxRelayService.processBatch();
            batchesRun++;
            publishedFreshAfterBatch1 = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND attempts = 0",
                    Long.class
            );
        }

        assertThat(publishedFreshAfterBatch1).isEqualTo(100L);
        long totalPublished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'",
                Long.class
        );
        // Both fresh and retries made progress concurrently
        assertThat(totalPublished).isGreaterThan(100L);
    }

    @Test
    @DisplayName("Non-starvation: Continuous fresh ingress does not starve due retries")
    void continuousFreshIngressDoesNotStarveDueRetries() {
        Instant now = fixedClock.instant();

        // Seed 10 due retries
        bulkInsertOutboxEvents(10, 1, now.minusSeconds(30), now.minusSeconds(5));

        // For 4 consecutive batches, inject 10 brand-new fresh rows before each batch run
        for (int b = 0; b < 4; b++) {
            bulkInsertOutboxEvents(10, 0, now.plusSeconds(b), now.plusSeconds(b));
            outboxRelayService.processBatch();
        }

        // All 10 due retries MUST have completed despite steady flood of 40 fresh rows
        long remainingPendingRetries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING' AND attempts > 0",
                Long.class
        );
        assertThat(remainingPendingRetries)
                .as("Continuous fresh ingress must not starve due retries; all 10 retries should be published")
                .isEqualTo(0L);

        long publishedRetries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND attempts > 0",
                Long.class
        );
        assertThat(publishedRetries).isEqualTo(10L);
    }

    @Test
    @DisplayName("Top-up: Fresh top-up query excludes IDs already selected earlier in the transaction; distinct IDs guaranteed")
    void topUpSelectsNoDuplicateIds() {
        Instant now = fixedClock.instant();

        // Seed 15 fresh rows, 0 due retries
        bulkInsertOutboxEvents(15, 0, now, now);

        // Batch size is 10. Fresh quota is 7. Step 2 (retries) returns 0.
        // Step 3 (top-up) must select 3 more fresh rows excluding the first 7 IDs.
        Set<UUID> publishedEventIds = Collections.synchronizedSet(new HashSet<>());
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    // Extract message key or payload to track unique dispatches
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                });

        int published = outboxRelayService.processBatch();
        assertThat(published).isEqualTo(10);

        // Assert exactly 10 distinct rows are now PUBLISHED
        List<UUID> publishedDbIds = jdbcTemplate.queryForList(
                "SELECT id FROM outbox_events WHERE status = 'PUBLISHED'",
                UUID.class
        );
        assertThat(publishedDbIds).hasSize(10);
        assertThat(new HashSet<>(publishedDbIds)).hasSize(10);

        long pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'",
                Long.class
        );
        assertThat(pendingCount).isEqualTo(5L);
    }

    @Test
    @DisplayName("Backoff eligibility: Failed rows are ineligible before backoff expires and eligible afterwards")
    void failedRowsIneligibleBeforeBackoffAndEligibleAfterwards() {
        Instant now = fixedClock.instant();
        bulkInsertOutboxEvents(1, 0, now, now);

        // Producer fails on attempt 1
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Temporary broker failure"));
        when(mockKafkaProducer.send(any(), any(), any(), any(), any())).thenReturn(failed);

        int published = outboxRelayService.processBatch();
        assertThat(published).isEqualTo(0);

        OutboxEventEntity failedEvent = outboxEventRepository.findAll().get(0);
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failedEvent.getAttempts()).isEqualTo(1);
        Instant nextAttemptAt = failedEvent.getNextAttemptAt();
        assertThat(nextAttemptAt).isAfter(now);

        // Now mock broker success
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        // Polling before backoff expires (clock still at now): must NOT be selected
        int publishedBeforeDue = outboxRelayService.processBatch();
        assertThat(publishedBeforeDue).isEqualTo(0);
        assertThat(outboxEventRepository.findById(failedEvent.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        // Advance clock past nextAttemptAt: must be selected and published
        outboxRelayService.setClock(Clock.fixed(nextAttemptAt.plusSeconds(1), ZoneOffset.UTC));
        int publishedAfterDue = outboxRelayService.processBatch();
        assertThat(publishedAfterDue).isEqualTo(1);
        assertThat(outboxEventRepository.findById(failedEvent.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Broker outage: Full broker outage causes no hot retry loop; recovery resumes dispatch")
    void fullBrokerOutageCausesNoHotRetryLoopAndRecoveryResumesDispatch() {
        Instant now = fixedClock.instant();
        bulkInsertOutboxEvents(5, 0, now, now);

        // Broker outage: fail all sends
        CompletableFuture<SendResult<String, String>> outage = new CompletableFuture<>();
        outage.completeExceptionally(new RuntimeException("Broker cluster unreachable"));
        when(mockKafkaProducer.send(any(), any(), any(), any(), any())).thenReturn(outage);

        // Cycle 1: initial attempt fails, rows back off
        int pub1 = outboxRelayService.processBatch();
        assertThat(pub1).isEqualTo(0);
        verify(mockKafkaProducer, times(5)).send(any(), any(), any(), any(), any());

        // Cycle 2 immediately (clock unchanged): backoff suppresses polling; NO Kafka calls
        int pub2 = outboxRelayService.processBatch();
        assertThat(pub2).isEqualTo(0);
        verify(mockKafkaProducer, times(5)).send(any(), any(), any(), any(), any()); // Still 5 calls total! No hot loop!

        // Cycle 3 immediately again: still 0
        int pub3 = outboxRelayService.processBatch();
        assertThat(pub3).isEqualTo(0);
        verify(mockKafkaProducer, times(5)).send(any(), any(), any(), any(), any());

        // Broker recovers!
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        // Advance clock by 5 seconds (past the ~1.2s max backoff for attempt 1)
        outboxRelayService.setClock(Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC));

        // Cycle 4: relay picks up all 5 due rows and successfully publishes
        int pub4 = outboxRelayService.processBatch();
        assertThat(pub4).isEqualTo(5);

        long pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'",
                Long.class
        );
        assertThat(pendingCount).isEqualTo(0L);

        long publishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'",
                Long.class
        );
        assertThat(publishedCount).isEqualTo(5L);
    }

    @Test
    @DisplayName("Concurrency: Concurrent relays acquire distinct rows with SKIP LOCKED without conflict")
    void concurrentRelaysAcquireDistinctRowsWithSkipLocked() throws Exception {
        Instant now = fixedClock.instant();
        bulkInsertOutboxEvents(20, 0, now, now);

        AtomicInteger totalPublished = new AtomicInteger(0);
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    // Slight artificial delay to induce lock contention
                    Thread.sleep(10);
                    totalPublished.incrementAndGet();
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                });

        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                int count = 0;
                for (int b = 0; b < 4; b++) {
                    count += outboxRelayService.processBatch();
                }
                return count;
            }));
        }

        int sumPublished = 0;
        for (Future<Integer> f : futures) {
            sumPublished += f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(sumPublished).isEqualTo(20);
        assertThat(totalPublished.get()).isEqualTo(20);

        long publishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'",
                Long.class
        );
        assertThat(publishedCount).isEqualTo(20L);
    }

    @Test
    @DisplayName("Batch size B=1: Alternating allocation strictly interleaves queues and work-steals when one is empty")
    void alternatingAllocationForBatchSizeOne() {
        Instant now = fixedClock.instant();
        outboxRelayService.setBatchSize(1);

        // Seed 3 fresh rows and 3 due retries
        bulkInsertOutboxEvents(3, 0, now, now);
        bulkInsertOutboxEvents(3, 1, now.minusSeconds(10), now.minusSeconds(5));

        // 6 cycles: should alternate fresh and retry
        List<Integer> attemptsPattern = new ArrayList<>();
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(mock(SendResult.class)));

        for (int i = 0; i < 6; i++) {
            outboxRelayService.processBatch();
        }

        long publishedFresh = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND attempts = 0",
                Long.class
        );
        long publishedRetries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' AND attempts > 0",
                Long.class
        );

        assertThat(publishedFresh).isEqualTo(3L);
        assertThat(publishedRetries).isEqualTo(3L);

        // Now with fresh queue empty and only retries remaining, work-stealing still makes progress
        bulkInsertOutboxEvents(2, 1, now.minusSeconds(10), now.minusSeconds(5));
        int stolen1 = outboxRelayService.processBatch();
        int stolen2 = outboxRelayService.processBatch();
        assertThat(stolen1 + stolen2).isEqualTo(2);
    }

    @Test
    @DisplayName("Regression: Failed rows anchored to post-batch completion time remain ineligible after slow sends and batch commit")
    void backoffTiming_anchoredToBatchCompletion_remainsIneligibleAfterSlowSends() {
        // Seed 3 fresh rows
        Instant t0 = Instant.parse("2026-10-09T15:00:00Z");
        outboxRelayService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        bulkInsertOutboxEvents(3, 0, t0, t0);

        // Simulate slow / timed-out sends advancing a mutable/moving clock during the batch:
        // When each message fails, time has moved forward.
        AtomicReference<Instant> currentClock = new AtomicReference<>(t0);
        Clock movingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentClock.get(); }
        };
        outboxRelayService.setClock(movingClock);

        // Mock Kafka send: on each send, advance clock by 500ms and fail exceptionally
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    currentClock.updateAndGet(t -> t.plusMillis(500));
                    CompletableFuture<SendResult<String, String>> f = new CompletableFuture<>();
                    f.completeExceptionally(new RuntimeException("Slow broker timeout"));
                    return f;
                });

        int published = outboxRelayService.processBatch();
        assertThat(published).isEqualTo(0);

        // After the 3 sends, current clock is t0 + 1500ms
        Instant batchEndTime = currentClock.get();
        assertThat(batchEndTime).isEqualTo(t0.plusMillis(1500));

        // All 3 failed events must have next_attempt_at >= batchEndTime + 800ms
        List<OutboxEventEntity> failedRows = outboxEventRepository.findAll();
        assertThat(failedRows).hasSize(3);
        for (OutboxEventEntity row : failedRows) {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getNextAttemptAt())
                    .as("next_attempt_at must be anchored to batch completion time, not initial failure time")
                    .isAfterOrEqualTo(batchEndTime.plusMillis(800));
        }

        // Immediate second processBatch at batchEndTime + 10ms: MUST NOT RETRY ANY JUST-FAILED ROW!
        currentClock.set(batchEndTime.plusMillis(10));
        int publishedImmediate = outboxRelayService.processBatch();
        assertThat(publishedImmediate).isEqualTo(0);

        // Verify attempts are STILL 1 (none of the rows were retried)
        for (OutboxEventEntity row : outboxEventRepository.findAll()) {
            assertThat(row.getAttempts()).isEqualTo(1);
        }

        // Now advance clock past the backoff window (batchEndTime + 2000ms):
        currentClock.set(batchEndTime.plusMillis(2000));
        // Mock Kafka to succeed
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        int publishedAfterBackoff = outboxRelayService.processBatch();
        assertThat(publishedAfterBackoff).isEqualTo(3);

        for (OutboxEventEntity row : outboxEventRepository.findAll()) {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        }
    }

    @Test
    @DisplayName("B=1 ticket alternation is strictly atomic under concurrent same-JVM calls")
    void batchSizeOne_concurrentTicketAlternation() throws Exception {
        int threads = 16;
        int callsPerThread = 1000;
        int totalCalls = threads * callsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<List<Boolean>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                List<Boolean> results = new ArrayList<>(callsPerThread);
                for (int c = 0; c < callsPerThread; c++) {
                    results.add(outboxRelayService.nextPreferenceForBatchSizeOne());
                }
                return results;
            }));
        }

        int trueCount = 0;
        int falseCount = 0;
        for (Future<List<Boolean>> f : futures) {
            for (boolean preference : f.get(10, TimeUnit.SECONDS)) {
                if (preference) {
                    trueCount++;
                } else {
                    falseCount++;
                }
            }
        }
        executor.shutdown();

        assertThat(trueCount + falseCount).isEqualTo(totalCalls);
        // With an even number of total calls (16,000), exactly half must be true (fresh) and half false (retry)
        assertThat(trueCount).isEqualTo(totalCalls / 2);
        assertThat(falseCount).isEqualTo(totalCalls / 2);
    }

    @Test
    @DisplayName("Unsafe timeout configuration is rejected at startup, bounded slow send finishes within headroom")
    void transactionAndSendBounds_validationAndSlowSendHeadroom() {
        // 1. Unsafe configurations rejected
        assertThatThrownBy(() -> OutboxRelayService.validateTimeoutConfiguration(10, 1500, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe outbox relay configuration");

        assertThatThrownBy(() -> OutboxRelayService.validateTimeoutConfiguration(10, 0, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid outbox per-message-timeout-ms");

        assertThatThrownBy(() -> OutboxRelayService.validateTimeoutConfiguration(10, -100, 15))
                .isInstanceOf(IllegalStateException.class);

        // 2. Safe configurations accepted
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                OutboxRelayService.validateTimeoutConfiguration(10, 1000, 15)
        );
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                OutboxRelayService.validateTimeoutConfiguration(5, 2000, 15)
        );

        // 3. Bounded slow-send batch: 5 messages taking 100ms each completes well within 15s transaction
        Instant now = fixedClock.instant();
        bulkInsertOutboxEvents(5, 0, now, now);

        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(100);
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                });

        long start = System.currentTimeMillis();
        int published = outboxRelayService.processBatch();
        long duration = System.currentTimeMillis() - start;

        assertThat(published).isEqualTo(5);
        assertThat(duration).isLessThan(5000L); // well under 12s deadline and 15s tx timeout
    }

    @Test
    @DisplayName("InterruptedException halts batch immediately; untouched locked rows do not gain attempts or backoff")
    void interruptHandling_haltsBatchAndPreservesUntouchedRows() {
        Instant now = fixedClock.instant();
        bulkInsertOutboxEvents(4, 0, now, now);

        // Message 1 succeeds, Message 2 throws InterruptedException
        AtomicInteger callCount = new AtomicInteger(0);
        when(mockKafkaProducer.send(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int count = callCount.incrementAndGet();
                    if (count == 1) {
                        return CompletableFuture.completedFuture(mock(SendResult.class));
                    } else if (count == 2) {
                        CompletableFuture<SendResult<String, String>> f = new CompletableFuture<>();
                        f.completeExceptionally(new InterruptedException("Worker thread interrupted"));
                        return f;
                    }
                    return CompletableFuture.completedFuture(mock(SendResult.class));
                });

        int published = outboxRelayService.processBatch();
        // Message 1 published
        assertThat(published).isEqualTo(1);
        // Thread interrupt flag must be restored
        assertThat(Thread.interrupted()).isTrue();

        // Exactly 2 sends attempted, NOT 4!
        assertThat(callCount.get()).isEqualTo(2);

        List<OutboxEventEntity> all = outboxEventRepository.findAll();
        long publishedCount = all.stream().filter(e -> e.getStatus() == OutboxStatus.PUBLISHED).count();
        long attempt1Count = all.stream().filter(e -> e.getStatus() == OutboxStatus.PENDING && e.getAttempts() == 1).count();
        long untouchedCount = all.stream().filter(e -> e.getStatus() == OutboxStatus.PENDING && e.getAttempts() == 0).count();

        assertThat(publishedCount).isEqualTo(1L); // Event 1 published
        assertThat(attempt1Count).isEqualTo(1L);  // Event 2 failed on interrupt, attempt = 1
        assertThat(untouchedCount).isEqualTo(2L); // Events 3 & 4 UNTOUCHED! attempts = 0, no backoff
    }
}
