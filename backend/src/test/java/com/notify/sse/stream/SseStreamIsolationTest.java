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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stalled client must not delay another stream's watchdog or another tenant's fan-out
 * (corrective cycle, adjacent finding A1).
 *
 * <p><b>What was wrong.</b> {@code SseStreamRegistry} ran the heartbeat and the watchdog on one two-thread
 * scheduler, and the heartbeat <em>writes to the client</em>. A stalled SSE write holds its thread until the
 * container gives up - measured at about 59 s on this stack, and uncancellable - so two stalled clients left no
 * thread to fire anyone's watchdog. The watchdog is what makes the serving deadline, and therefore the tenant cap,
 * real: that is a correctness defect, not a latency one. Redis hints were drained inline on the listener callback
 * thread as well, so one stalled client stalled fan-out for every tenant on the instance.
 *
 * <p>These tests run the timer pool with a <b>single</b> thread, the strongest form of the claim: even one timer
 * thread stays free while a client write is blocked.
 */
class SseStreamIsolationTest {

    /** A lease factory under the test's control, so a stream can be given an already-lapsed deadline. */
    private static final class StubCap extends SseStreamCapService {

        private final MonotonicTimeSource time;
        private volatile long nextDeadlineOffsetNanos = Duration.ofMinutes(5).toNanos();
        private final AtomicLong releases = new AtomicLong();

        StubCap(SseProperties props, MonotonicTimeSource time) {
            super(null, props, time);
            this.time = time;
        }

        @Override
        public Admission admit(UUID tenantId, String recipientRef, String streamId) {
            return new Admission.Granted(new SseStreamLease(
                    tenantId, recipientRef, streamId, "gen:" + streamId, "gen",
                    System.currentTimeMillis() + 90_000,
                    time.nanos() + nextDeadlineOffsetNanos));
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

    /** Blocks inside {@code send} until released, exactly as a client that has stopped reading does. */
    private static final class BlockingEmitter extends SseEmitter {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            writeStarted.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    /** Records what it was asked to send, and never blocks. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<Object> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            sent.add(builder);
        }
    }

    private static SseProperties props() {
        return new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 2);
    }

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private MonotonicTimeSource time;
    private StubCap cap;
    private SseStreamRegistry registry;
    private ScheduledExecutorService timers;
    private ExecutorService dispatcher;
    private ExecutorService maintenance;
    private final List<InAppMessageView> rowsForB = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        time = new MonotonicTimeSource();
        cap = new StubCap(props(), time);
        timers = Executors.newScheduledThreadPool(1);          // one thread on purpose
        dispatcher = Executors.newFixedThreadPool(2);
        maintenance = Executors.newFixedThreadPool(2);

        registry = new SseStreamRegistry(cap, messagesForB(), alwaysActive(), props(), time,
                new SimpleMeterRegistry(), timers, dispatcher, maintenance);
    }

    /** No rows for any tenant: used by the contrast case, where only thread occupancy matters. */
    private InAppMessageService emptyMessages() {
        return messagesFor(List.of());
    }

    private InAppMessageService messagesForB() {
        return messagesFor(rowsForB);
    }

    private InAppMessageService messagesFor(List<InAppMessageView> rows) {
        return new InAppMessageService() {
            @Override
            public List<InAppMessageView> ascendingAbove(UUID tenantId, String recipientRef, long cursor, int limit) {
                if (!tenantId.equals(tenantB)) {
                    return List.of();
                }
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

    @AfterEach
    void tearDown() {
        timers.shutdownNow();
        dispatcher.shutdownNow();
        maintenance.shutdownNow();
    }

    private String streamIdOf(UUID tenantId) {
        return registry.sessionIds().stream()
                .filter(id -> registry.session(id).orElseThrow().lease().tenantId().equals(tenantId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("A1 a blocked client write leaves the timer thread free, so another stream's watchdog still fires")
    void aBlockedWriteDoesNotDelayAnotherStreamsWatchdog() throws Exception {
        BlockingEmitter stalled = new BlockingEmitter();
        registry.openWith(stalled, tenantA, "stalled-user", 0L);
        String stalledId = streamIdOf(tenantA);

        RecordingEmitter healthy = new RecordingEmitter();
        registry.openWith(healthy, tenantB, "healthy-user", 0L);
        String healthyId = streamIdOf(tenantB);

        // The heartbeat's authority work runs on the timer thread; only the write is dispatched.
        long before = System.nanoTime();
        timers.submit(() -> registry.runHeartbeat(stalledId)).get(3, TimeUnit.SECONDS);
        assertThat(stalled.writeStarted.await(3, TimeUnit.SECONDS))
                .as("the stalled client's write is in progress on the dispatcher")
                .isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - before))
                .as("runHeartbeat itself did not wait for the write")
                .isLessThan(Duration.ofSeconds(2));

        // Now let the healthy stream's authority lapse. Its pre-armed watchdog is five minutes out, so only the
        // explicit call below can close it - which is what makes this an assertion about the timer thread.
        InAppStreamSession healthySession = registry.session(healthyId).orElseThrow();
        SseStreamLease lapsed = new SseStreamLease(tenantB, "healthy-user", healthyId, "m", "gen",
                System.currentTimeMillis(), time.nanos() - 1_000_000L);
        healthySession.lease(lapsed);
        assertThat(healthySession.authorityExpired()).isTrue();

        // The claim: with the other client's write still blocked, the single timer thread runs this watchdog.
        Future<?> watchdog = timers.submit(() -> registry.expire(healthyId));
        watchdog.get(3, TimeUnit.SECONDS);

        assertThat(registry.session(healthyId)).as("the lapsed stream was closed on time").isEmpty();
        // The Redis release is no longer synchronous on this thread: finding R1 moved the blocking half of a close
        // (the emitter completion and the Redis release) to the bounded dispatcher, so the watchdog can revoke
        // authority without waiting. It still happens - promptly - so it is awaited rather than assumed.
        long releaseDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (cap.releases.get() == 0 && System.nanoTime() < releaseDeadline) {
            Thread.sleep(20);
        }
        assertThat(cap.releases.get()).as("and its Redis slot was released").isPositive();
        assertThat(stalled.release.getCount()).as("while the stalled write is still blocked").isEqualTo(1);
        assertThat(registry.session(stalledId)).isPresent();

        stalled.release.countDown();
    }

    @Test
    @DisplayName("A1 a Redis hint never drains inline, so one stalled client cannot stall fan-out for other tenants")
    void aHintDoesNotBlockOnAStalledClient() throws Exception {
        BlockingEmitter stalled = new BlockingEmitter();
        registry.openWith(stalled, tenantA, "stalled-user", 0L);
        String stalledId = streamIdOf(tenantA);

        // Put the stalled stream's write in flight.
        timers.submit(() -> registry.runHeartbeat(stalledId)).get(3, TimeUnit.SECONDS);
        assertThat(stalled.writeStarted.await(3, TimeUnit.SECONDS)).isTrue();

        RecordingEmitter healthy = new RecordingEmitter();
        registry.openWith(healthy, tenantB, "healthy-user", 0L);
        rowsForB.add(new InAppMessageView(7L, UUID.randomUUID(), "Subject", "Body", null,
                java.time.Instant.parse("2026-10-15T00:00:00Z")));

        long before = System.nanoTime();
        registry.onHint(tenantB, "healthy-user");        // this is the Redis listener's callback thread
        Duration elapsed = Duration.ofNanos(System.nanoTime() - before);

        assertThat(elapsed).as("onHint returned without writing anything itself").isLessThan(Duration.ofSeconds(1));
        // And the healthy stream's row really is delivered while the other client is stuck.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (healthy.sent.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(healthy.sent).as("the other tenant's stream drained on the dispatcher").isNotEmpty();
        assertThat(stalled.release.getCount()).isEqualTo(1);

        stalled.release.countDown();
    }

    @Test
    @DisplayName("A1 contrast: with the write back on the timer thread, the watchdog is starved - the original defect")
    void theOldInlineArrangementStarvesTheWatchdog() throws Exception {
        // A caller-runs dispatcher reproduces the original code exactly: the heartbeat wrote to the client on the
        // same thread that fires watchdogs. Nothing else about the registry changes, so this isolates the defect.
        SseStreamRegistry inline = new SseStreamRegistry(cap, emptyMessages(), alwaysActive(), props(), time,
                new SimpleMeterRegistry(), timers, Runnable::run, Runnable::run);

        BlockingEmitter stalled = new BlockingEmitter();
        inline.openWith(stalled, tenantA, "stalled-user", 0L);
        String stalledId = inline.sessionIds().iterator().next();

        Future<?> heartbeat = timers.submit(() -> inline.runHeartbeat(stalledId));
        assertThat(stalled.writeStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // The single timer thread is now inside the blocked write, so nothing else it is asked to do can run.
        Future<?> probe = timers.submit(() -> { });
        assertThat(catchTimeout(probe))
                .as("this is the defect: the thread that fires every watchdog is stuck in one client's write")
                .isTrue();

        stalled.release.countDown();
        heartbeat.get(5, TimeUnit.SECONDS);
        assertThat(catchTimeout(probe)).as("and it recovers only once that write finally ends").isFalse();
    }

    private static boolean catchTimeout(Future<?> future) throws Exception {
        try {
            future.get(1, TimeUnit.SECONDS);
            return false;
        } catch (java.util.concurrent.TimeoutException e) {
            return true;
        }
    }

    @Test
    @DisplayName("A1 at most one dispatched task per stream, so the dispatcher queue is bounded by the session count")
    void dispatchIsDeduplicatedPerStream() throws Exception {
        BlockingEmitter stalled = new BlockingEmitter();
        registry.openWith(stalled, tenantA, "stalled-user", 0L);
        String stalledId = streamIdOf(tenantA);

        timers.submit(() -> registry.runHeartbeat(stalledId)).get(3, TimeUnit.SECONDS);
        assertThat(stalled.writeStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Fifty more hints for the same stalled stream. None may queue behind the one already in flight.
        for (int i = 0; i < 50; i++) {
            registry.onHint(tenantA, "stalled-user");
        }
        assertThat(registry.dispatchedTaskCount())
                .as("one task in flight; the dirty flag carries the rest, so nothing accumulates")
                .isEqualTo(1);

        stalled.release.countDown();
    }
}
