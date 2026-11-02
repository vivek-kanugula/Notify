package com.notify.sse.stream;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.common.model.TenantStatus;
import com.notify.inapp.model.InAppMessageView;
import com.notify.inapp.service.InAppMessageService;
import com.notify.sse.config.SseProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit bounds on queued stream work and on local admission (/ , finding R2).
 *
 * <p><b>What was wrong.</b> Two claims in the previous cycle did not hold.
 * <ul>
 *   <li>{@code Executors.newFixedThreadPool} pairs a fixed thread count with an <b>unbounded</b>
 *       {@code LinkedBlockingQueue}. The per-stream in-flight marker stops duplicates for a <em>live</em> stream, but
 *       a task queued for a stream that then closed stayed queued, so churn accumulated work with no bound following
 *       from the registry size - which is exactly what the javadoc claimed.</li>
 *   <li>{@code openWith} guarded the instance limit with {@code sessions.size() >= max} followed by a {@code put}:
 *       check-then-insert, so concurrent opens could seat more streams than {@code max-streams-per-instance}.</li>
 * </ul>
 *
 * <p>Both contrasts below are run against the pre-fix arrangement in the same test, so the difference is measured
 * rather than asserted from memory.
 */
class SseStreamResourceBoundTest {

    private static final int MAX_STREAMS = 4;
    private static final int QUEUE_CAPACITY = 4;

    private static SseProperties props() {
        return new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, MAX_STREAMS, QUEUE_CAPACITY, 1);
    }

    /** Never blocks, and records what it was asked to send. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> ids = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            String text = frame.toString();
            int at = text.indexOf("id:");
            if (at >= 0) {
                ids.add(text.substring(at + 3, text.indexOf('\n', at)).trim());
            }
        }
    }

    private static class StubCap extends SseStreamCapService {
        protected final MonotonicTimeSource time;
        private volatile boolean refuse;
        private final AtomicInteger releases = new AtomicInteger();

        StubCap(SseProperties props, MonotonicTimeSource time) {
            super(null, props, time);
            this.time = time;
        }

        @Override
        public Admission admit(UUID tenantId, String recipientRef, String streamId) {
            if (refuse) {
                return new Admission.Refused("CAP", 1000);
            }
            return new Admission.Granted(new SseStreamLease(tenantId, recipientRef, streamId,
                    "m:" + streamId, "gen", System.currentTimeMillis() + 90_000,
                    time.nanos() + Duration.ofMinutes(5).toNanos()));
        }

        @Override
        public RefreshResult refresh(SseStreamLease lease) {
            return new RefreshResult(lease, "OK");
        }

        @Override
        public void releaseQuietly(SseStreamLease lease) {
            releases.incrementAndGet();
        }
    }

    private final UUID tenant = UUID.randomUUID();
    private final List<InAppMessageView> rows = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> blockers = new CopyOnWriteArrayList<>();

    private MonotonicTimeSource time;
    private StubCap cap;
    private ScheduledExecutorService timers;
    private ThreadPoolExecutor dispatcher;
    private ExecutorService maintenance;
    private SseStreamRegistry registry;

    @BeforeEach
    void setUp() {
        time = new MonotonicTimeSource();
        cap = new StubCap(props(), time);
        timers = Executors.newScheduledThreadPool(2);
        dispatcher = (ThreadPoolExecutor) SseStreamRegistry.boundedPool("test-dispatch", 1, QUEUE_CAPACITY);
        maintenance = SseStreamRegistry.boundedPool("test-maintenance", 1, QUEUE_CAPACITY);
        registry = new SseStreamRegistry(cap, messages(), alwaysActive(), props(), time,
                new SimpleMeterRegistry(), timers, dispatcher, maintenance);
    }

    @AfterEach
    void tearDown() {
        blockers.forEach(CountDownLatch::countDown);
        timers.shutdownNow();
        dispatcher.shutdownNow();
        maintenance.shutdownNow();
    }

    private InAppMessageService messages() {
        return new InAppMessageService() {
            @Override
            public List<InAppMessageView> ascendingAbove(UUID tenantId, String recipientRef, long cursor, int limit) {
                return rows.stream().filter(row -> row.seq() > cursor).limit(limit).toList();
            }

            @Override
            public List<InAppMessageView> page(UUID tenantId, String recipientRef, Long before, int size) {
                return List.of();
            }

            @Override
            public long countUnread(UUID tenantId, String recipientRef) {
                return 0;
            }

            @Override
            public boolean markRead(UUID tenantId, String recipientRef, long seq) {
                return false;
            }
        };
    }

    private ApiKeyStatusCacheService alwaysActive() {
        return new ApiKeyStatusCacheService(null, null, null) {
            @Override
            public Optional<TenantStatus> getTenantStatus(UUID tenantId) {
                return Optional.of(TenantStatus.ACTIVE);
            }
        };
    }

    /** Occupies every worker of {@code pool} until the returned latch is released. */
    private CountDownLatch occupy(ExecutorService pool, int workers) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(workers);
        blockers.add(release);
        for (int i = 0; i < workers; i++) {
            pool.execute(() -> {
                busy.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(busy.await(5, TimeUnit.SECONDS)).as("every worker is occupied").isTrue();
        return release;
    }

    private String openOne(String recipient) {
        java.util.Set<String> before = registry.sessionIds();
        Object opened = registry.openWith(new RecordingEmitter(), tenant, recipient, 0L);
        assertThat(opened).isInstanceOf(SseEmitter.class);
        return registry.sessionIds().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("R2 churn with occupied workers keeps the queue inside its configured bound")
    void churnStaysInsideTheQueueBound() throws Exception {
        occupy(dispatcher, 1);

        // Four times the instance limit, opened, woken and closed while the single worker is blocked.
        int churn = MAX_STREAMS * 4;
        for (int i = 0; i < churn; i++) {
            String id = openOne("churn-" + i);
            registry.onHint(tenant, "churn-" + i);          // queues a drain for this stream
            assertThat(dispatcher.getQueue().size())
                    .as("the queue never exceeds notify.sse.queue-capacity, at iteration %d", i)
                    .isLessThanOrEqualTo(QUEUE_CAPACITY);
            registry.close(id, "churn");                     // the stream leaves; its queued work must not persist
            assertThat(dispatcher.getQueue().size()).isLessThanOrEqualTo(QUEUE_CAPACITY);
        }

        assertThat(registry.openStreams()).isZero();
        assertThat(registry.inFlightDispatches()).as("no in-flight marker survives a close").isZero();
        assertThat(registry.freeLocalSlots()).isEqualTo(MAX_STREAMS);
        assertThat(registry.rejectedDispatchCount())
                .as("saturation was reached and counted rather than hidden")
                .isPositive();

        // Release the worker: every task queued for a closed stream drops instead of doing work.
        blockers.forEach(CountDownLatch::countDown);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (dispatcher.getQueue().size() > 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(dispatcher.getQueue()).as("the queue drains completely").isEmpty();
        assertThat(registry.droppedObsoleteTaskCount())
                .as("queued work for closed streams was dropped, not executed")
                .isPositive();
    }

    @Test
    @DisplayName("R2 contrast: the pre-fix unbounded queue grows past the session count under the same churn")
    void theUnboundedQueueGrowsPastTheSessionCount() throws Exception {
        // The previous arrangement, reproduced exactly: a fixed pool, whose LinkedBlockingQueue has no bound.
        ThreadPoolExecutor unbounded = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        ExecutorService otherMaintenance = Executors.newFixedThreadPool(1);
        SseStreamRegistry old = new SseStreamRegistry(cap, messages(), alwaysActive(), props(), time,
                new SimpleMeterRegistry(), timers, unbounded, otherMaintenance);
        try {
            occupy(unbounded, 1);

            int churn = MAX_STREAMS * 4;
            for (int i = 0; i < churn; i++) {
                java.util.Set<String> before = old.sessionIds();
                Object opened = old.openWith(new RecordingEmitter(), tenant, "old-" + i, 0L);
                if (!(opened instanceof SseEmitter)) {
                    continue;
                }
                String id = old.sessionIds().stream().filter(s -> !before.contains(s)).findFirst().orElseThrow();
                old.onHint(tenant, "old-" + i);
                old.close(id, "churn");
            }

            assertThat(old.openStreams()).as("no stream is live any more").isZero();
            assertThat(unbounded.getQueue().size())
                    .as("yet the queue holds work far beyond the session count and beyond queue-capacity: "
                            + "this is the defect R2 reports")
                    .isGreaterThan(QUEUE_CAPACITY);
        } finally {
            blockers.forEach(CountDownLatch::countDown);
            unbounded.shutdownNow();
            otherMaintenance.shutdownNow();
        }
    }

    @Test
    @DisplayName("R2 concurrent admissions never seat more streams than the instance limit, across tenants")
    void concurrentAdmissionRespectsTheInstanceLimit() throws Exception {
        int attempts = 24;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService openers = Executors.newFixedThreadPool(attempts);
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                UUID tenantId = i % 2 == 0 ? tenant : UUID.randomUUID();   // different tenants too
                String recipient = "concurrent-" + i;
                futures.add(openers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    outcomes.add(registry.openWith(new RecordingEmitter(), tenantId, recipient, 0L));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            openers.shutdownNow();
        }

        long seated = outcomes.stream().filter(SseEmitter.class::isInstance).count();
        long refused = outcomes.stream().filter(SseStreamRegistry.Refusal.class::isInstance).count();
        assertThat(seated + refused).isEqualTo(attempts);
        assertThat(seated).as("never more than the instance limit, however many threads raced").isEqualTo(MAX_STREAMS);
        assertThat(registry.openStreams()).isEqualTo(MAX_STREAMS);
        assertThat(registry.freeLocalSlots()).isZero();
        assertThat(outcomes.stream()
                .filter(SseStreamRegistry.Refusal.class::isInstance)
                .map(SseStreamRegistry.Refusal.class::cast)
                .allMatch(refusal -> "INSTANCE_LIMIT".equals(refusal.reason())))
                .isTrue();

        // Closing gives every permit back, so the instance can seat streams again.
        for (String id : registry.sessionIds()) {
            registry.close(id, "test");
        }
        assertThat(registry.freeLocalSlots()).isEqualTo(MAX_STREAMS);
        assertThat(registry.inFlightDispatches()).isZero();
    }

    @Test
    @DisplayName("R2 contrast: a check-then-insert guard over-seats under the same concurrency")
    void checkThenInsertOverSeats() throws Exception {
        // A faithful model of the pre-fix guard - `if (sessions.size() >= max) refuse; ... sessions.put(...)` - with
        // the same thread count. It is a model, not the registry: the point is that the shape itself is unsafe.
        // The interleaving is forced with a latch rather than left to chance, so the demonstration is deterministic:
        // every thread completes its size check before any thread inserts, which is exactly the window the real
        // guard left open while it did its tenant check and its Redis admission.
        Map<String, Object> seats = new ConcurrentHashMap<>();
        int attempts = 24;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(attempts);
        ExecutorService threads = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String id = "seat-" + i;
                futures.add(threads.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    boolean admitted = seats.size() < MAX_STREAMS;      // the check
                    checked.countDown();
                    checked.await(5, TimeUnit.SECONDS);                 // the window the admission work sat in
                    if (admitted) {
                        seats.put(id, new Object());                    // the insert
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(seats.size())
                .as("check-then-insert admits past the limit; the semaphore in the registry cannot")
                .isGreaterThan(MAX_STREAMS);
    }

    @Test
    @DisplayName("R2 a refused admission releases its reservation, so refusals cannot exhaust the instance")
    void refusedAdmissionsReleaseTheirReservation() {
        cap.refuse = true;
        for (int i = 0; i < MAX_STREAMS * 3; i++) {
            Object outcome = registry.openWith(new RecordingEmitter(), tenant, "refused-" + i, 0L);
            assertThat(outcome).isInstanceOf(SseStreamRegistry.Refusal.class);
            assertThat(((SseStreamRegistry.Refusal) outcome).reason()).isEqualTo("CAP");
        }
        assertThat(registry.freeLocalSlots())
                .as("a failed admission must not keep its permit")
                .isEqualTo(MAX_STREAMS);

        cap.refuse = false;
        assertThat(registry.openWith(new RecordingEmitter(), tenant, "now-allowed", 0L))
                .isInstanceOf(SseEmitter.class);
    }

    @Test
    @DisplayName("R2 a healthy stream drains after capacity returns, with no lost cursor and no duplicate emission")
    void aHealthyStreamDrainsOnceCapacityReturns() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        java.util.Set<String> before = registry.sessionIds();
        rows.add(row(1));
        assertThat(registry.openWith(emitter, tenant, "healthy", 0L)).isInstanceOf(SseEmitter.class);
        String streamId = registry.sessionIds().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
        InAppStreamSession session = registry.session(streamId).orElseThrow();
        assertThat(emitter.ids).containsExactly("1");            // the open-time drain, on the request thread

        // Saturate: the worker is busy and the queue is full of work for other streams.
        occupy(dispatcher, 1);
        for (int i = 0; i < QUEUE_CAPACITY + 4; i++) {
            String other = openOne("filler-" + i);
            registry.onHint(tenant, "filler-" + i);
            registry.close(other, "filler");
        }

        // Two more rows arrive while the stream cannot be drained. Its hint is very likely rejected.
        rows.add(row(2));
        rows.add(row(3));
        registry.onHint(tenant, "healthy");
        assertThat(registry.rejectedDispatchCount()).isPositive();

        // Capacity returns. The dirty flag survived a rejection, so a heartbeat drains everything, in order, once.
        blockers.forEach(CountDownLatch::countDown);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (emitter.ids.size() < 3 && System.nanoTime() < deadline) {
            registry.runHeartbeat(streamId);
            TimeUnit.MILLISECONDS.sleep(20);
        }

        assertThat(emitter.ids).as("in order, nothing lost, nothing twice").containsExactly("1", "2", "3");
        assertThat(session.cursor()).isEqualTo(3L);
        assertThat(registry.session(streamId)).isPresent();
    }

    // ─── C2: the timer-registration race ───────────────────────────────────────────────────────────────────────────

    /**
     * Runs the first one-shot task inline, at schedule time.
     *
     * <p>That one-shot is {@code openWith}'s watchdog, so this forces the interleaving the race needs: the watchdog
     * closes the session while the opening thread is still inside its registration block. Nothing else about the
     * scheduler changes - the periodic heartbeat is scheduled normally, and {@code removeOnCancelPolicy} matches
     * production so a cancelled task leaves the queue.
     */
    private static final class InlineFirstWatchdogScheduler extends ThreadPoolExecutorBackedScheduler {
        private final AtomicInteger oneShots = new AtomicInteger();

        InlineFirstWatchdogScheduler() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (oneShots.incrementAndGet() == 1) {
                command.run();                                    // the watchdog fires before handles are published
                return super.schedule(() -> { }, 0, TimeUnit.MILLISECONDS);
            }
            return super.schedule(command, delay, unit);
        }
    }

    /** Named separately only so the override above reads clearly. */
    private static class ThreadPoolExecutorBackedScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor {
        ThreadPoolExecutorBackedScheduler(int threads) {
            super(threads);
        }
    }

    @Test
    @DisplayName("C2 a watchdog that closes the stream during registration leaves no timer, heartbeat or permit")
    void aWatchdogDuringRegistrationLeavesNothingBehind() throws Exception {
        InlineFirstWatchdogScheduler inlineTimers = new InlineFirstWatchdogScheduler();
        StubCap lapsedCap = new StubCap(props(), time) {
            @Override
            public Admission admit(UUID tenantId, String recipientRef, String streamId) {
                // An already-lapsed lease, so the watchdog this scheduler runs inline really does expire it.
                return new Admission.Granted(new SseStreamLease(tenantId, recipientRef, streamId,
                        "m:" + streamId, "gen", System.currentTimeMillis(), time.nanos() - 1_000_000L));
            }
        };
        ExecutorService ownDispatcher = SseStreamRegistry.boundedPool("c2-dispatch", 1, QUEUE_CAPACITY);
        ExecutorService ownMaintenance = SseStreamRegistry.boundedPool("c2-maintenance", 1, QUEUE_CAPACITY);
        SseStreamRegistry raced = new SseStreamRegistry(lapsedCap, messages(), alwaysActive(), props(), time,
                new SimpleMeterRegistry(), inlineTimers, ownDispatcher, ownMaintenance);
        try {
            Object opened = raced.openWith(new RecordingEmitter(), tenant, "raced", 0L);
            assertThat(opened).isInstanceOf(SseEmitter.class);

            assertThat(raced.openStreams()).as("the inline watchdog closed it").isZero();
            assertThat(raced.registeredTimerEntries())
                    .as("no timer entry is retained for a stream that is already gone")
                    .isZero();
            assertThat(raced.freeLocalSlots())
                    .as("the permit came back exactly once")
                    .isEqualTo(MAX_STREAMS);

            // The decisive assertion: nothing recurring is left on the scheduler. A heartbeat registered after the
            // close would reschedule for ever; with removeOnCancelPolicy a cancelled one leaves the queue.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (inlineTimers.getQueue().size() > 0 && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(inlineTimers.getQueue()).as("no recurring heartbeat survives the close").isEmpty();
        } finally {
            inlineTimers.shutdownNow();
            ownDispatcher.shutdownNow();
            ownMaintenance.shutdownNow();
        }
    }

    @Test
    @DisplayName("C2 contrast: scheduling before publishing the handles retains a live heartbeat for a closed stream")
    void schedulingBeforePublishingRetainsALiveHeartbeat() throws Exception {
        // The previous ordering, modelled against the same scheduler: schedule, let the close run, then publish.
        // The close finds nothing to cancel, and what is published afterwards keeps firing.
        ThreadPoolExecutorBackedScheduler timer = new ThreadPoolExecutorBackedScheduler(1);
        timer.setRemoveOnCancelPolicy(true);
        Map<String, List<java.util.concurrent.ScheduledFuture<?>>> published = new ConcurrentHashMap<>();
        try {
            String streamId = "old-ordering";
            java.util.concurrent.ScheduledFuture<?> heartbeat =
                    timer.scheduleWithFixedDelay(() -> { }, 20, 20, TimeUnit.MILLISECONDS);
            java.util.concurrent.ScheduledFuture<?> watchdog = timer.schedule(() -> { }, 10_000, TimeUnit.MILLISECONDS);

            // the close that interleaves: nothing has been published yet, so it cancels nothing
            List<java.util.concurrent.ScheduledFuture<?>> cancelled = published.remove(streamId);
            assertThat(cancelled).as("the close had no handles to cancel").isNull();

            published.put(streamId, List.of(heartbeat, watchdog));      // the opening thread, too late

            assertThat(published).as("a stale entry for a closed stream").containsKey(streamId);
            assertThat(heartbeat.isCancelled()).isFalse();
            assertThat(timer.getQueue().size())
                    .as("and a recurring task still scheduled: the defect C2 reports")
                    .isPositive();
        } finally {
            timer.shutdownNow();
        }
    }

    // ─── C1: a global bound on retained cleanup work ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 churn with every cleanup refused keeps pending cleanup inside its own bound")
    void pendingCleanupStaysInsideItsBound() throws Exception {
        // Occupy the worker and fill the queue with cleanups that cannot run, so every later cleanup is refused and
        // retried from the scheduler's unbounded delayed queue - the path that used to retain a session per retry.
        occupy(dispatcher, 1);

        int churn = QUEUE_CAPACITY * 8;
        for (int i = 0; i < churn; i++) {
            String id = openOne("cleanup-churn-" + i);
            registry.close(id, "churn");
            assertThat(registry.pendingCleanups())
                    .as("pending cleanup never exceeds notify.sse.queue-capacity, at iteration %d", i)
                    .isLessThanOrEqualTo(QUEUE_CAPACITY);
            assertThat(registry.freeCleanupSlots() + registry.pendingCleanups())
                    .as("permits and markers stay in step")
                    .isEqualTo(QUEUE_CAPACITY);
        }

        assertThat(registry.openStreams()).isZero();
        assertThat(registry.freeLocalSlots())
                .as("admission keeps working: the instance is not throttled by its cleanup backlog")
                .isEqualTo(MAX_STREAMS);
        assertThat(registry.abandonedCleanupCount())
                .as("the bound was reached and reported rather than silently exceeded")
                .isPositive();

        // Capacity returns: every reservation comes back, so the bound is not a one-way ratchet.
        blockers.forEach(CountDownLatch::countDown);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (registry.pendingCleanups() > 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(registry.pendingCleanups()).as("no reservation leaks on success").isZero();
        assertThat(registry.freeCleanupSlots()).isEqualTo(QUEUE_CAPACITY);
        assertThat(cap.releases.get()).as("and the Redis members really were released").isPositive();
    }

    /**
     * The same relationships at 1/50 scale, so a cleanup retry is paced at 500 ms instead of the production 25 s.
     * {@code SseProperties} asserts the safety relationships, so this cannot be a quietly unsafe set.
     */
    private static SseProperties fastRetryProps() {
        return new SseProperties(
                Duration.ofSeconds(3), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(500),
                Duration.ofMillis(3500), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, MAX_STREAMS, QUEUE_CAPACITY, 1);
    }

    @Test
    @DisplayName("C1 a cleanup reservation is returned on success, on abandonment and at shutdown")
    void cleanupReservationsAreAlwaysReturned() throws Exception {
        // 1. success, on the shared registry
        String ordinary = openOne("ordinary");
        registry.close(ordinary, "ordinary");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (registry.pendingCleanups() > 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(registry.pendingCleanups()).isZero();
        assertThat(registry.freeCleanupSlots()).isEqualTo(QUEUE_CAPACITY);

        // 2 and 3 need the retry interval to be short, so they get their own registry with a scaled timing set.
        SseProperties fast = fastRetryProps();
        ScheduledExecutorService fastTimers = Executors.newScheduledThreadPool(1);
        ExecutorService fastDispatcher = SseStreamRegistry.boundedPool("c1-dispatch", 1, QUEUE_CAPACITY);
        ExecutorService fastMaintenance = SseStreamRegistry.boundedPool("c1-maintenance", 1, QUEUE_CAPACITY);
        SseStreamRegistry fastRegistry = new SseStreamRegistry(new StubCap(fast, time), messages(), alwaysActive(),
                fast, time, new SimpleMeterRegistry(), fastTimers, fastDispatcher, fastMaintenance);
        try {
            // 2. abandonment: the dispatcher is gone, so neither the submit nor any retry can be accepted.
            fastDispatcher.shutdownNow();
            java.util.Set<String> before = fastRegistry.sessionIds();
            assertThat(fastRegistry.openWith(new RecordingEmitter(), tenant, "doomed", 0L))
                    .isInstanceOf(SseEmitter.class);
            String doomed = fastRegistry.sessionIds().stream()
                    .filter(id -> !before.contains(id)).findFirst().orElseThrow();
            fastRegistry.close(doomed, "dispatcher gone");

            long abandonDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (fastRegistry.abandonedCleanupCount() == 0 && System.nanoTime() < abandonDeadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(fastRegistry.abandonedCleanupCount())
                    .as("the retries were exhausted and the cleanup was given up on")
                    .isPositive();
            assertThat(fastRegistry.pendingCleanups()).as("abandonment returns the reservation too").isZero();
            assertThat(fastRegistry.freeCleanupSlots()).isEqualTo(QUEUE_CAPACITY);

            // 3. shutdown while a cleanup is still waiting to retry: shutdownNow drops the scheduler's delayed
            //    queue, so the registry returns those reservations itself rather than leaving them leaked.
            java.util.Set<String> beforePending = fastRegistry.sessionIds();
            assertThat(fastRegistry.openWith(new RecordingEmitter(), tenant, "pending-at-shutdown", 0L))
                    .isInstanceOf(SseEmitter.class);
            String pending = fastRegistry.sessionIds().stream()
                    .filter(id -> !beforePending.contains(id)).findFirst().orElseThrow();
            fastRegistry.close(pending, "pending");
            fastRegistry.shutdown();

            assertThat(fastRegistry.pendingCleanups()).isZero();
            assertThat(fastRegistry.freeCleanupSlots()).isEqualTo(QUEUE_CAPACITY);
            assertThat(fastRegistry.openStreams()).isZero();
            assertThat(fastRegistry.registeredTimerEntries()).as("no timer entry survives shutdown").isZero();
            assertThat(fastRegistry.freeLocalSlots()).isEqualTo(MAX_STREAMS);
        } finally {
            fastTimers.shutdownNow();
            fastDispatcher.shutdownNow();
            fastMaintenance.shutdownNow();
        }
    }

    private static InAppMessageView row(long seq) {
        return new InAppMessageView(seq, UUID.randomUUID(), "s" + seq, "b" + seq, null,
                Instant.parse("2026-10-15T00:00:00Z"));
    }
}
