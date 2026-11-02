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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timer pool must not wait on an emitter's write lock (/ , finding R1).
 *
 * <p><b>What the earlier fix missed, and why its fixture could not show it.</b> Finding A1 moved {@code send} off
 * the timer pool, which was necessary but not sufficient. In the resolved Spring 7.0.9 source,
 * {@code ResponseBodyEmitter.send(Set<DataWithMediaType>)} acquires {@code writeLock} (line 236) and
 * {@code ResponseBodyEmitter.complete()} acquires the <b>same</b> {@code writeLock} (line 276). So completing an
 * emitter whose write is stalled blocks for exactly as long as that write does - and the watchdog completed
 * emitters on the timer thread. Two lapsed streams with stalled clients could therefore occupy both production
 * timer threads. {@link SseStreamIsolationTest}'s {@code BlockingEmitter} overrides
 * {@code send(SseEventBuilder)} and never touches {@code writeLock}, so its {@code complete()} returned at once and
 * the suite stayed green.
 *
 * <p><b>What the fixture here models.</b> {@link LockHoldingEmitter} does not simulate the lock - it takes the real
 * {@code writeLock} that {@link SseEmitter} inherits, which is {@code protected} and therefore reachable from a
 * subclass. While it is held, {@code complete()} on that same emitter blocks inside Spring's own code, exactly as it
 * does when a socket write has stalled. Nothing about Spring is stubbed.
 *
 * <p><b>What the repair guarantees, stated precisely.</b> After the deadline a stream starts no new emission -
 * message, heartbeat comment or {@code resync} - because every emission path checks the monotonic deadline before
 * writing. The watchdog revokes that authority immediately and without waiting for any lock or network call. It does
 * <b>not</b> interrupt a write already blocked in the container and makes no promise about when the socket closes;
 * that remains the accepted connector-dependent delay. The pre-write check is an independent safety boundary, so
 * this defect on its own never implied tenant-cap oversubscription - what it threatened was the promptness of
 * revocation for <em>other</em> streams.
 */
class SseStreamTimerIsolationTest {

    /** Two threads: the production {@code notify-sse-timers} pool size, so the claim is about the real arrangement. */
    private static final int PRODUCTION_TIMER_THREADS = 2;

    /**
     * Holds the emitter's real {@code writeLock} for as long as the test wants, which is what a stalled socket write
     * does. {@code complete()} on the same emitter then blocks inside {@code ResponseBodyEmitter}.
     */
    private static final class LockHoldingEmitter extends SseEmitter {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger emissions = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            emissions.incrementAndGet();
            writeLock.lock();                     // the very lock complete() will contend on
            try {
                writeStarted.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("fixture was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                writeLock.unlock();
            }
        }

        /** Blocks until the stalled write is actually in progress, so the test never races its own fixture. */
        boolean awaitStalled() throws InterruptedException {
            return writeStarted.await(5, TimeUnit.SECONDS);
        }
    }

    /** Counts emissions and never blocks. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> frames = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
        }
    }

    private static final class StubCap extends SseStreamCapService {
        private final MonotonicTimeSource time;
        private volatile long deadlineOffsetNanos = Duration.ofMinutes(5).toNanos();
        private final AtomicInteger releases = new AtomicInteger();

        StubCap(SseProperties props, MonotonicTimeSource time) {
            super(null, props, time);
            this.time = time;
        }

        @Override
        public Admission admit(UUID tenantId, String recipientRef, String streamId) {
            return new Admission.Granted(new SseStreamLease(tenantId, recipientRef, streamId,
                    "m:" + streamId, "gen", System.currentTimeMillis() + 90_000,
                    time.nanos() + deadlineOffsetNanos));
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

    private static SseProperties props() {
        return new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 4);
    }

    private final UUID tenant = UUID.randomUUID();
    private final List<InAppMessageView> rows = new CopyOnWriteArrayList<>();
    private final List<LockHoldingEmitter> stalled = new CopyOnWriteArrayList<>();

    private MonotonicTimeSource time;
    private StubCap cap;
    private ScheduledExecutorService timers;
    private ExecutorService dispatcher;
    private ExecutorService maintenance;
    private SseStreamRegistry registry;

    @BeforeEach
    void setUp() {
        time = new MonotonicTimeSource();
        cap = new StubCap(props(), time);
        timers = Executors.newScheduledThreadPool(PRODUCTION_TIMER_THREADS);
        dispatcher = SseStreamRegistry.boundedPool("test-dispatch", 4, 64);
        maintenance = SseStreamRegistry.boundedPool("test-maintenance", 2, 64);
        registry = new SseStreamRegistry(cap, messages(), alwaysActive(), props(), time,
                new SimpleMeterRegistry(), timers, dispatcher, maintenance);
    }

    @AfterEach
    void tearDown() {
        // Always release the fixtures, or the pools would hold their threads for the full 30 s await.
        stalled.forEach(emitter -> emitter.release.countDown());
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

    /** Opens a stream whose client stalls on its first write, and leaves that write in progress. */
    private String openStalled(String recipient) throws Exception {
        LockHoldingEmitter emitter = new LockHoldingEmitter();
        stalled.add(emitter);
        rows.add(new InAppMessageView(rows.size() + 1L, UUID.randomUUID(), "s", "b", null,
                Instant.parse("2026-10-15T00:00:00Z")));
        String id = openAndIdentify(emitter, recipient);
        assertThat(emitter.awaitStalled()).as("the stalled write is in progress").isTrue();
        return id;
    }

    private String openAndIdentify(SseEmitter emitter, String recipient) {
        java.util.Set<String> before = registry.sessionIds();
        // openWith drains on the calling thread, so a stalled emitter would block the test itself; run it off-thread.
        ExecutorService opener = Executors.newSingleThreadExecutor();
        try {
            opener.submit(() -> registry.openWith(emitter, tenant, recipient, 0L));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                Optional<String> fresh = registry.sessionIds().stream().filter(id -> !before.contains(id)).findFirst();
                if (fresh.isPresent()) {
                    return fresh.get();
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("the stream was never seated");
        } finally {
            opener.shutdown();
        }
    }

    private void lapse(String streamId) {
        InAppStreamSession session = registry.session(streamId).orElseThrow();
        SseStreamLease lease = session.lease();
        session.lease(new SseStreamLease(lease.tenantId(), lease.recipientRef(), lease.streamId(), lease.member(),
                lease.generation(), System.currentTimeMillis(), time.nanos() - 1_000_000L));
        assertThat(session.authorityExpired()).isTrue();
    }

    private static boolean timesOut(Future<?> future, long millis) throws Exception {
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        }
    }

    @Test
    @DisplayName("R1 completion really does wait behind a stalled send: the fixture models Spring's own lock")
    void completionWaitsBehindAStalledSend() throws Exception {
        LockHoldingEmitter emitter = new LockHoldingEmitter();
        stalled.add(emitter);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            writer.submit(() -> {
                emitter.send(SseEmitter.event().name("message").data("x"));
                return null;
            });
            assertThat(emitter.awaitStalled()).isTrue();

            ExecutorService completer = Executors.newSingleThreadExecutor();
            try {
                Future<?> completion = completer.submit(emitter::complete);
                assertThat(timesOut(completion, 1000))
                        .as("ResponseBodyEmitter.complete() contends for the same writeLock as send()")
                        .isTrue();

                emitter.release.countDown();
                completion.get(5, TimeUnit.SECONDS);      // and it finishes the moment the write lets go
            } finally {
                completer.shutdownNow();
            }
        } finally {
            emitter.release.countDown();
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("R1 contrast: completing on the timer thread starves an unrelated stream's watchdog")
    void completingOnTheTimerThreadStarvesAnotherWatchdog() throws Exception {
        String first = openStalled("stalled-1");
        String second = openStalled("stalled-2");

        // The original arrangement, reproduced exactly: the watchdog completes the emitter itself.
        List<InAppStreamSession> lapsedSessions = List.of(
                registry.session(first).orElseThrow(), registry.session(second).orElseThrow());
        List<Future<?>> occupied = new CopyOnWriteArrayList<>();
        for (InAppStreamSession session : lapsedSessions) {
            occupied.add(timers.submit(() -> session.stop("old watchdog: revoke and complete inline")));
        }
        for (Future<?> future : occupied) {
            assertThat(timesOut(future, 1000))
                    .as("both production timer threads are now inside complete(), waiting on a stalled write")
                    .isTrue();
        }

        Future<?> unrelatedWatchdog = timers.submit(() -> { });
        assertThat(timesOut(unrelatedWatchdog, 1000))
                .as("this is the defect: an unrelated stream's watchdog cannot run")
                .isTrue();

        stalled.forEach(emitter -> emitter.release.countDown());
        for (Future<?> future : occupied) {
            future.get(10, TimeUnit.SECONDS);
        }
        unrelatedWatchdog.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("R1 the repaired watchdog revokes authority at once and leaves the timer pool free")
    void theWatchdogRevokesWithoutWaitingForTheWriteLock() throws Exception {
        String firstStalled = openStalled("stalled-1");
        String secondStalled = openStalled("stalled-2");
        RecordingEmitter healthy = new RecordingEmitter();
        String healthyId = openAndIdentify(healthy, "healthy");

        lapse(firstStalled);
        lapse(secondStalled);

        // Both watchdogs run on the production-sized timer pool while both writes are still stalled.
        long before = System.nanoTime();
        Future<?> firstExpiry = timers.submit(() -> registry.expire(firstStalled));
        Future<?> secondExpiry = timers.submit(() -> registry.expire(secondStalled));
        firstExpiry.get(3, TimeUnit.SECONDS);
        secondExpiry.get(3, TimeUnit.SECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - before))
                .as("neither watchdog waited for an emitter lock or a network call")
                .isLessThan(Duration.ofSeconds(2));

        // Authority is gone immediately, and the sessions are out of the registry.
        assertThat(registry.session(firstStalled)).isEmpty();
        assertThat(registry.session(secondStalled)).isEmpty();
        assertThat(registry.streamsAbleToEmit()).isEqualTo(1);

        // The unrelated stream's watchdog still runs promptly, and its own heartbeat still works.
        lapse(healthyId);
        timers.submit(() -> registry.expire(healthyId)).get(3, TimeUnit.SECONDS);
        assertThat(registry.session(healthyId)).isEmpty();
        assertThat(registry.freeLocalSlots())
                .as("every permit came back")
                .isEqualTo(props().maxStreamsPerInstance());

        // And the writes are still blocked throughout: nothing above depended on them finishing.
        assertThat(stalled.get(0).release.getCount()).isEqualTo(1);
        assertThat(stalled.get(1).release.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R1 a lapsed stream starts no further message, heartbeat or resync emission")
    void aLapsedStreamEmitsNothing() {
        RecordingEmitter emitter = new RecordingEmitter();
        String streamId = openAndIdentify(emitter, "soon-lapsed");
        InAppStreamSession session = registry.session(streamId).orElseThrow();
        int emittedBeforeLapse = emitter.frames.size();

        lapse(streamId);

        rows.add(new InAppMessageView(9_999L, UUID.randomUUID(), "after", "the deadline", null,
                Instant.parse("2026-10-15T00:00:00Z")));
        session.markDirty();
        session.drain();                                      // a message emission
        assertThat(session.heartbeat()).isFalse();            // a heartbeat comment
        session.resync(InAppStreamSession.ResyncReason.GAP_TOO_LARGE);   // a control frame

        assertThat(emitter.frames.size())
                .as("no emission of any kind after the deadline")
                .isEqualTo(emittedBeforeLapse);
        assertThat(session.isStopped()).isTrue();
    }

    @Test
    @DisplayName("R1 repeated and racing close, timeout and disconnect callbacks clean up exactly once")
    void racingClosesCleanUpExactlyOnce() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        String streamId = openAndIdentify(emitter, "racing");

        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService closers = Executors.newFixedThreadPool(racers);
        try {
            List<Future<?>> futures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < racers; i++) {
                String reason = "racer-" + i;
                futures.add(closers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    registry.close(streamId, reason);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            closers.shutdownNow();
        }

        assertThat(registry.session(streamId)).isEmpty();
        assertThat(registry.openStreams()).isZero();
        assertThat(registry.inFlightDispatches()).as("no in-flight marker left behind").isZero();
        assertThat(registry.freeLocalSlots())
                .as("the permit is released exactly once, however many threads raced")
                .isEqualTo(props().maxStreamsPerInstance());
        // The Redis member is released once: whichever thread won sessions.remove owns the cleanup.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (cap.releases.get() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(cap.releases.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("R1 shutdown revokes every stream's authority and returns while writes are still stalled")
    void shutdownDoesNotWaitForStalledWrites() throws Exception {
        String first = openStalled("stalled-1");
        String second = openStalled("stalled-2");

        ExecutorService shutter = Executors.newSingleThreadExecutor();
        try {
            Future<?> shutdown = shutter.submit(registry::shutdown);
            // Bounded: shutdown gives the cleanup pools a short grace period and then abandons the queue.
            shutdown.get(15, TimeUnit.SECONDS);
        } finally {
            shutter.shutdownNow();
        }

        assertThat(registry.openStreams()).isZero();
        assertThat(registry.session(first)).isEmpty();
        assertThat(registry.session(second)).isEmpty();
        assertThat(stalled.get(0).release.getCount()).as("and it did not wait for the writes").isEqualTo(1);
    }
}
