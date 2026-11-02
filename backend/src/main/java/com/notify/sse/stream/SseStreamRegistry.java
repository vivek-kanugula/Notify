package com.notify.sse.stream;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.common.model.TenantStatus;
import com.notify.inapp.service.InAppMessageService;
import com.notify.sse.config.SseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns every live SSE stream on this instance (/ , ).
 *
 * <p>Responsibilities, each kept deliberately small:
 * <ul>
 *   <li><b>admission</b> through {@link SseStreamCapService} — the only path that may create a lease;</li>
 *   <li><b>the watchdog</b>, armed at the lease's local serving deadline. It marks the stream stopped and completes
 *       the emitter. It does <em>not</em> interrupt a blocked write: measurement on the resolved stack showed that
 *       neither {@code complete()}, {@code completeWithError()} nor the async timeout can. The guarantee is "no new
 *       emission after the deadline", which the session's pre-write check provides;</li>
 *   <li><b>the heartbeat</b>, which both writes the keep-alive comment and triggers a PostgreSQL drain, so a stream
 *       stays correct even with Redis entirely unavailable;</li>
 *   <li><b>the lease refresh</b>, which validates generation, membership and expiry. A refusal attempts exactly one
 *       counted re-admission; if that is refused too the stream closes at or before its existing deadline, so a
 *       failed re-admission can never increase the number of streams able to emit;</li>
 *   <li><b>cleanup</b> on every exit path — completion, timeout, error, client disconnect, tenant deactivation and
 *       application shutdown — which releases the Redis member, cancels the timers and drops the session.</li>
 * </ul>
 *
 * <p><b>Three pools, and exactly what each may block on (, corrected again by findings R1 and R2).</b> A write to
 * a slow client blocks for as long as the container takes to abandon it — about 59 s on this stack, uncancellable.
 * Worse, {@code ResponseBodyEmitter.send(Set)} and {@code ResponseBodyEmitter.complete()} take the <b>same</b>
 * {@code writeLock} in the resolved Spring 7.0.9 source, so <em>completing</em> a stalled emitter blocks just as
 * long as the write does. Moving only {@code send} off the timer pool was therefore not enough: two watchdogs
 * completing stalled emitters could still occupy both timer threads. The split is now by <em>what a task is allowed
 * to wait on</em>:
 * <ul>
 *   <li><b>{@code scheduler}</b> (2 threads) — <b>local work only</b>. It fires the watchdog, re-arms watchdogs and
 *       submits the heartbeat tick. It takes no emitter lock, opens no socket and issues no Redis or PostgreSQL
 *       call, so nothing a client or a broker does can delay it. Revoking a lapsed stream's authority happens here
 *       and is a compare-and-set.</li>
 *   <li><b>{@code maintenance}</b> (bounded) — the tenant-status check and the Redis lease refresh or re-admission.
 *       Both are network calls, bounded by their own configured timeouts rather than by a client. A rejected or
 *       delayed tick simply does not renew the lease, and the watchdog then revokes authority on time: the failure
 *       mode is a stream that stops early, never one that keeps emitting.</li>
 *   <li><b>{@code dispatcher}</b> (bounded) — everything that may wait on a client: the heartbeat comment, the
 *       PostgreSQL drain, the {@code resync} frame, and the emitter completion plus Redis release of a closed
 *       stream. Cleanup belongs here precisely because it contends for the same lock as the writes.</li>
 * </ul>
 *
 * <p><b>The bounds, which are now real rather than argued (finding R2).</b> Both bounded pools are
 * {@code ThreadPoolExecutor}s with {@code notify.sse.dispatch-threads} threads and an
 * {@code ArrayBlockingQueue} of {@code notify.sse.queue-capacity}, and they <b>abort</b> on saturation; neither ever
 * runs a task on the caller's thread, so a timer or a Redis listener callback can never be made to do socket work.
 * The earlier claim that the dispatcher's queue "can never exceed the session count" was wrong: the per-stream
 * in-flight marker stops duplicates for a <em>live</em> stream, but a queued task whose stream then closed stayed
 * queued, so churn accumulated work without any bound following from the registry size. Queued tasks now resolve
 * their session by id when they run and drop immediately if it has gone, the in-flight marker is cleared on close,
 * and the queue capacity is an explicit configured number.
 *
 * <p><b>What saturation costs, stated rather than hidden.</b> A rejected drain loses nothing: the session's dirty
 * flag survives, PostgreSQL remains authoritative and the next heartbeat drains. A rejected heartbeat tick skips one
 * keep-alive. A rejected cleanup is retried from the scheduler a bounded number of times; if every attempt is
 * refused, the emitter is completed by the container's own async timeout at {@code max-lifetime} and the Redis
 * member lapses on Redis's clock at {@code lease-ttl}, so a tenant slot is never leaked permanently. None of this
 * extends any stream's authority to emit, because that is decided only by the monotonic pre-write deadline check.
 */
@Component
public class SseStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseStreamRegistry.class);

    /** Retries of a refused cleanup before it is left to the async timeout and the Redis lease. */
    private static final int MAX_CLEANUP_ATTEMPTS = 3;

    private final SseStreamCapService cap;
    private final InAppMessageService messages;
    private final ApiKeyStatusCacheService tenantStatus;
    private final SseProperties props;
    private final MonotonicTimeSource time;
    private final MeterRegistry meters;
    private final ScheduledExecutorService scheduler;
    private final Executor dispatcher;
    private final Executor maintenance;
    private final List<ExecutorService> ownedPools = new CopyOnWriteArrayList<>();

    private final Map<String, InAppStreamSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<ScheduledFuture<?>>> timers = new ConcurrentHashMap<>();
    /** At most one dispatched task per stream at a time. Cleared on close, so a closed stream retains nothing. */
    private final Set<String> dispatchInFlight = ConcurrentHashMap.newKeySet();
    /**
     * The local per-instance stream reservation.
     *
     * <p>A {@code sessions.size()} check followed by a {@code put} is check-then-insert: two concurrent opens could
     * both pass it and seat more streams than {@code max-streams-per-instance}. A permit is taken before any
     * admission work and released on <em>every</em> path that does not end with a seated session, and exactly once
     * on close - by whichever thread wins {@code sessions.remove}.
     */
    private final Semaphore localSlots;
    /**
     * The global bound on retained cleanup work (finding C1).
     *
     * <p>A closed session's emitter and lease stay reachable until its cleanup runs, and a refused cleanup waits in
     * the scheduler's unbounded delayed-task queue. The per-session retry limit does not bound how many sessions are
     * in that state, and the admission permit is already back, so the instance keeps seating new streams. One permit
     * is held for each closed session awaiting cleanup, so the total is {@code notify.sse.queue-capacity}.
     */
    private final Semaphore cleanupSlots;
    /** Which streams hold a cleanup reservation, so returning one is idempotent per stream. */
    private final Map<String, Boolean> cleanupReserved = new ConcurrentHashMap<>();
    private final AtomicLong dispatchedTasks = new AtomicLong();
    private final AtomicLong abandonedCleanups = new AtomicLong();
    private final AtomicLong rejectedDispatches = new AtomicLong();
    private final AtomicLong droppedObsoleteTasks = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public SseStreamRegistry(
            SseStreamCapService cap,
            InAppMessageService messages,
            ApiKeyStatusCacheService tenantStatus,
            SseProperties props,
            MonotonicTimeSource time,
            ObjectProvider<MeterRegistry> meters
    ) {
        this(cap, messages, tenantStatus, props, time, meters.getIfAvailable(SimpleMeterRegistry::new),
                defaultScheduler(),
                boundedPool("notify-sse-dispatch", props.dispatchThreads(), props.queueCapacity()),
                boundedPool("notify-sse-maintenance", props.dispatchThreads(), props.queueCapacity()));
    }

    /**
     * For tests: explicit pools, so watchdogs, client writes, cleanup and lease maintenance can be driven by hand -
     * and so a test can prove that a stalled write and its equally stalled completion do not delay another stream's
     * watchdog.
     */
    public SseStreamRegistry(
            SseStreamCapService cap,
            InAppMessageService messages,
            ApiKeyStatusCacheService tenantStatus,
            SseProperties props,
            MonotonicTimeSource time,
            MeterRegistry meters,
            ScheduledExecutorService scheduler,
            Executor dispatcher,
            Executor maintenance
    ) {
        this.cap = cap;
        this.messages = messages;
        this.tenantStatus = tenantStatus;
        this.props = props;
        this.time = time;
        this.meters = meters;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.maintenance = maintenance;
        this.localSlots = new Semaphore(props.maxStreamsPerInstance());
        this.cleanupSlots = new Semaphore(props.queueCapacity());
        if (dispatcher instanceof ExecutorService owned) {
            ownedPools.add(owned);
        }
        if (maintenance instanceof ExecutorService owned && maintenance != dispatcher) {
            ownedPools.add(owned);
        }
        meters.gauge("notify.inapp.streams_open", sessions, Map::size);
        meters.gauge("notify.inapp.local_slots_free", localSlots, Semaphore::availablePermits);
    }

    private static ScheduledExecutorService defaultScheduler() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor =
                new java.util.concurrent.ScheduledThreadPoolExecutor(2, namedDaemons("notify-sse-timers"));
        // Without this, a cancelled heartbeat or watchdog lingers in the delay queue for its whole period.
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * A pool with an explicit bound on both running and waiting work.
     *
     * <p>Deliberately not {@code Executors.newFixedThreadPool}, which pairs a fixed thread count with an
     * <b>unbounded</b> {@code LinkedBlockingQueue} - the defect R2 reports. The rejection policy is abort, never
     * caller-runs: a caller here may be a timer thread or a Redis listener callback, and running socket work on
     * either is the failure this whole arrangement exists to prevent.
     */
    static ExecutorService boundedPool(String name, int threads, int queueCapacity) {
        return new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity),
                namedDaemons(name),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    private static java.util.concurrent.ThreadFactory namedDaemons(String name) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Runs work for a <em>live</em> stream off the calling thread, at most once per stream at a time.
     *
     * <p>Three properties, each of which a test pins:
     * <ul>
     *   <li><b>skipping is safe.</b> Callers set the session's dirty flag before dispatching, the drain loop re-reads
     *       it, and a heartbeat at most {@code notify.sse.heartbeat} later drains again. PostgreSQL is authoritative,
     *       so nothing is lost;</li>
     *   <li><b>rejection is safe for the same reason</b>, and is counted rather than hidden. It never falls back to
     *       running on the caller's thread, which here may be a timer or a Redis listener callback;</li>
     *   <li><b>a queued task resolves its session when it runs.</b> A task whose stream closed in the meantime does
     *       nothing, which is what keeps churn from retaining work for streams that no longer exist (finding R2).</li>
     * </ul>
     */
    private void dispatch(String streamId, java.util.function.Consumer<InAppStreamSession> task) {
        if (!dispatchInFlight.add(streamId)) {
            return;
        }
        try {
            dispatcher.execute(() -> {
                try {
                    InAppStreamSession current = sessions.get(streamId);
                    if (current == null || current.isStopped()) {
                        droppedObsoleteTasks.incrementAndGet();
                        return;
                    }
                    task.accept(current);
                } catch (RuntimeException e) {
                    log.warn("Dispatched SSE work failed for stream {} ({})", streamId, e.getClass().getName());
                } finally {
                    dispatchInFlight.remove(streamId);
                }
            });
            dispatchedTasks.incrementAndGet();
        } catch (RejectedExecutionException e) {
            dispatchInFlight.remove(streamId);
            rejectedDispatches.incrementAndGet();
            meters.counter("notify.inapp.dispatch_rejected").increment();
            log.debug("SSE work for stream {} was rejected; the dirty flag carries it to the next heartbeat",
                    streamId);
        } catch (RuntimeException e) {
            dispatchInFlight.remove(streamId);
            throw e;
        }
    }

    /**
     * Queues the blocking half of a close: completing the emitter and releasing the Redis member.
     *
     * <p>Outside the per-stream in-flight guard on purpose - a stream with a drain in flight must still be cleaned
     * up. On rejection it is retried from the scheduler, a local submit, a bounded number of times.
     *
     * <p><b>Every pending cleanup holds a reservation (finding C1).</b> The retry limit bounds attempts for one
     * session; it says nothing about how many sessions are awaiting cleanup, and a scheduled retry sits in the
     * scheduler's <b>unbounded</b> delayed-task queue while retaining the closed session and its emitter - with the
     * local admission permit already released, so the instance keeps accepting new streams. {@link #close} therefore
     * takes one permit from {@code cleanupSlots} before the first submit, the same permit covers that session's
     * pending, queued and running cleanup, and it is returned on completion, on abandonment and at shutdown. Total
     * retained cleanup work is consequently {@code notify.sse.queue-capacity}, whatever the churn.
     *
     * <p>Abandonment is not stranding: the container's own async timeout completes the emitter at
     * {@code max-lifetime} and Redis prunes the member at {@code lease-ttl}.
     */
    private void submitCleanup(InAppStreamSession session, String reason, int attempt) {
        String streamId = session.streamId();
        try {
            dispatcher.execute(() -> {
                try {
                    session.releaseEmitter();
                } finally {
                    try {
                        cap.releaseQuietly(session.lease());
                    } finally {
                        releaseCleanupSlot(streamId);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            meters.counter("notify.inapp.cleanup_rejected").increment();
            if (attempt >= MAX_CLEANUP_ATTEMPTS) {
                abandonCleanup(session, reason, attempt + 1 + " refusals");
                return;
            }
            try {
                scheduler.schedule(() -> submitCleanup(session, reason, attempt + 1),
                        props.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                abandonCleanup(session, reason, "the registry is shutting down");
            }
        }
    }

    /** Gives up on cleaning a stream up actively, and - the point of C1 - gives its reservation back. */
    private void abandonCleanup(InAppStreamSession session, String reason, String why) {
        abandonedCleanups.incrementAndGet();
        meters.counter("notify.inapp.cleanup_abandoned").increment();
        log.warn("SSE cleanup for stream {} abandoned after {} ({}); the emitter will be completed by the async "
                        + "timeout and the Redis member will lapse on its lease",
                session.streamId(), why, reason);
        releaseCleanupSlot(session.streamId());
    }

    /** Idempotent per stream: only the thread that removes the marker returns the permit. */
    private void releaseCleanupSlot(String streamId) {
        if (cleanupReserved.remove(streamId) != null) {
            cleanupSlots.release();
        }
    }

    /** Refused admission, carrying the readiness hint the token API returns to the browser (condition C2). */
    public record Refusal(String reason, long retryAfterMillis) {
    }

    /**
     * Opens a stream, or returns the refusal. {@code lastEventId} is the client's cursor; the caller has already
     * validated it (an invalid cursor is 422, never a silent zero).
     */
    public Object open(UUID tenantId, String recipientRef, long lastEventId) {
        return openWith(new SseEmitter(props.maxLifetime().toMillis()), tenantId, recipientRef, lastEventId);
    }

    /** The same path with a caller-supplied emitter, so a test can block or record what a stream writes. */
    Object openWith(SseEmitter emitter, UUID tenantId, String recipientRef, long lastEventId) {
        // The local reservation, taken atomically rather than inferred from sessions.size() (finding R2). It is a
        // memory guard only; the tenant cap is the Redis lease and is unchanged.
        if (!localSlots.tryAcquire()) {
            return new Refusal("INSTANCE_LIMIT", props.heartbeat().toMillis());
        }
        boolean seated = false;
        try {
            if (!tenantActive(tenantId)) {
                return new Refusal("TENANT_NOT_ACTIVE", props.leaseTtl().toMillis());
            }

            String streamId = UUID.randomUUID().toString();
            SseStreamCapService.Admission admission = cap.admit(tenantId, recipientRef, streamId);
            if (admission instanceof SseStreamCapService.Admission.Refused refused) {
                meters.counter("notify.inapp.admissions_refused", "reason", refused.reason()).increment();
                return new Refusal(refused.reason(), refused.retryAfterMillis());
            }
            SseStreamLease lease = ((SseStreamCapService.Admission.Granted) admission).lease();

            InAppStreamSession session = new InAppStreamSession(emitter, lease, lastEventId, messages, props, time);
            sessions.put(streamId, session);
            seated = true;                      // from here the permit belongs to the session, released by close()

            // The handle list is published BEFORE anything is scheduled (finding C2). Scheduling first left a window
            // in which a watchdog - armed at `max(1, remaining)`, so possibly 1 ms - could close the session while
            // `timers` still held nothing for it: that close cancelled nothing, and the opening thread then
            // registered a recurring heartbeat and a watchdog for an already-closed stream, which reschedule for
            // ever and keep a `timers` entry alive. Publishing first means any concurrent close finds and cancels
            // whatever has been registered so far, which also covers a partial registration failure.
            CopyOnWriteArrayList<ScheduledFuture<?>> handles = new CopyOnWriteArrayList<>();
            timers.put(streamId, handles);
            try {
                emitter.onCompletion(() -> close(streamId, "completed"));
                emitter.onTimeout(() -> close(streamId, "lifetime reached"));
                emitter.onError(error -> close(streamId, "error: " + error.getClass().getSimpleName()));

                long heartbeatMs = props.heartbeat().toMillis();
                handles.add(scheduler.scheduleWithFixedDelay(
                        () -> triggerHeartbeat(streamId), heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS));
                handles.add(scheduler.schedule(
                        () -> expire(streamId), Math.max(1, lease.remainingMillis(time)), TimeUnit.MILLISECONDS));

                // Subscribe-then-drain, on the request's own thread: a hint arriving during this first drain only
                // sets the flag, which the drain observes.
                session.drain();
            } catch (RuntimeException e) {
                // A seated session whose wiring failed would otherwise keep its permit, its Redis member and its
                // place in `sessions` with no timer able to close it. Go through the ordinary close so the permit,
                // the member and the emitter are all released exactly once, then let the caller see the failure.
                log.warn("Seating stream {} failed after admission ({}); closing it", streamId, e.getClass().getName());
                close(streamId, "seating failed: " + e.getClass().getSimpleName());
                throw e;
            } finally {
                // A close that ran while this method was registering may have removed the entry before a later
                // handle was added to it. Cancel the local list, not the map's, so nothing we scheduled survives a
                // stream that is already gone - and no recurring heartbeat is left behind.
                if (!sessions.containsKey(streamId)) {
                    timers.remove(streamId);
                    handles.forEach(future -> future.cancel(false));
                }
            }
            return emitter;
        } finally {
            if (!seated) {
                localSlots.release();
            }
        }
    }

    /**
     * Wakes every stream of this tenant whose recipient matches the hint ().
     *
     * <p>Called on the Redis listener's callback thread. The drain itself writes to clients, so it is dispatched: a
     * stalled client must not delay fan-out for the rest of the instance (finding A1). This method therefore returns
     * without having written anything.
     */
    public void onHint(UUID tenantId, String recipientRef) {
        for (Map.Entry<String, InAppStreamSession> entry : sessions.entrySet()) {
            InAppStreamSession session = entry.getValue();
            SseStreamLease lease = session.lease();
            if (lease.tenantId().equals(tenantId)
                    && (recipientRef == null || recipientRef.isEmpty() || lease.recipientRef().equals(recipientRef))) {
                session.markDirty();
                dispatch(entry.getKey(), InAppStreamSession::drain);
            }
        }
    }

    /** Marks every stream dirty — used after a (re)subscribe, so recovery does not wait for a heartbeat. */
    public void drainAll() {
        for (Map.Entry<String, InAppStreamSession> entry : sessions.entrySet()) {
            entry.getValue().markDirty();
            dispatch(entry.getKey(), InAppStreamSession::drain);
        }
    }

    public int openStreams() {
        return sessions.size();
    }

    /** Streams on this instance that may still start a new emission — the quantity the cap limits. */
    public long streamsAbleToEmit() {
        return sessions.values().stream()
                .filter(session -> !session.isStopped() && !session.authorityExpired())
                .count();
    }

    public Optional<InAppStreamSession> session(String streamId) {
        return Optional.ofNullable(sessions.get(streamId));
    }

    Set<String> sessionIds() {
        return Set.copyOf(sessions.keySet());
    }

    /** Tasks actually handed to the dispatcher. A skipped dispatch does not count: see {@link #dispatch}. */
    long dispatchedTaskCount() {
        return dispatchedTasks.get();
    }

    /** Dispatches refused because the bounded queue was full. The dirty flag carries that work forward. */
    long rejectedDispatchCount() {
        return rejectedDispatches.get();
    }

    /** Queued tasks that found their stream already closed and did nothing. */
    long droppedObsoleteTaskCount() {
        return droppedObsoleteTasks.get();
    }

    /** Streams this instance may still seat, from the atomic local reservation rather than from a size check. */
    int freeLocalSlots() {
        return localSlots.availablePermits();
    }

    /** Closed streams whose cleanup is pending, queued or running. Bounded by {@code notify.sse.queue-capacity}. */
    int pendingCleanups() {
        return cleanupReserved.size();
    }

    int freeCleanupSlots() {
        return cleanupSlots.availablePermits();
    }

    /** Cleanups given up on, either at the bound or after exhausting retries. Their reservations were returned. */
    long abandonedCleanupCount() {
        return abandonedCleanups.get();
    }

    /** Streams with a registered timer handle list. A closed stream must retain none. */
    int registeredTimerEntries() {
        return timers.size();
    }

    /** Streams with a live dispatched task, queued or running. Bounded by the queue capacity plus the thread count. */
    int inFlightDispatches() {
        return dispatchInFlight.size();
    }

    /**
     * The scheduled half of a heartbeat: a local check and one submit, nothing else (finding R1).
     *
     * <p>This is what the timer thread runs. It issues no Redis or PostgreSQL call and takes no emitter lock, so the
     * timer pool stays free for watchdogs no matter what any client or broker is doing. A rejected submit skips one
     * tick: the lease is simply not renewed, and the watchdog revokes authority at the existing deadline.
     */
    void triggerHeartbeat(String streamId) {
        if (!sessions.containsKey(streamId)) {
            return;
        }
        try {
            maintenance.execute(() -> runHeartbeat(streamId));
        } catch (RejectedExecutionException e) {
            meters.counter("notify.inapp.maintenance_rejected").increment();
            log.debug("Heartbeat tick for stream {} was rejected; the watchdog remains the backstop", streamId);
        }
    }

    /**
     * One heartbeat tick's authority work. Public so tests can drive it directly.
     *
     * <p>Runs on the {@code maintenance} pool: the tenant-status check and the Redis lease refresh are network calls,
     * bounded by their own configured timeouts, and they must not sit on the timer pool. Only the keep-alive comment
     * and the drain go to the dispatcher, because only those wait on a client.
     *
     * <p>A lease that cannot be renewed, or a tenant that is no longer active, closes the stream immediately -
     * authority is revoked before this method returns, and only the blocking remainder of the close is queued. No
     * explanatory {@code resync} frame is sent on those two paths: the client's connection ends and its next token
     * request carries the reason and the retry interval readably (condition C2), which is strictly more than a frame
     * could tell it.
     */
    public void runHeartbeat(String streamId) {
        InAppStreamSession session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        try {
            if (!tenantActive(session.lease().tenantId())) {
                close(streamId, "tenant not active");
                return;
            }
            if (!refreshLease(session)) {
                close(streamId, "lease lost");
                return;
            }
            dispatch(streamId, live -> {
                if (!live.heartbeat()) {
                    close(streamId, "heartbeat stopped the stream");
                }
            });
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed for stream {} ({})", streamId, e.getClass().getName());
            close(streamId, "heartbeat threw");
        }
    }

    /**
     * Refreshes the lease, or attempts exactly one counted re-admission. Returns false when the stream must stop at
     * or before its existing deadline.
     */
    private boolean refreshLease(InAppStreamSession session) {
        SseStreamCapService.RefreshResult result = cap.refresh(session.lease());
        if (result.renewed()) {
            session.lease(result.lease());
            rearmWatchdog(session);
            return true;
        }
        meters.counter("notify.inapp.lease_refresh_failures", "reason", result.reason()).increment();
        if ("REDIS_UNAVAILABLE".equals(result.reason())) {
            // Keep serving to the existing deadline: correctness comes from PostgreSQL, authority from the clock.
            return !session.authorityExpired();
        }
        SseStreamLease previous = session.lease();
        SseStreamCapService.Admission readmission =
                cap.admit(previous.tenantId(), previous.recipientRef(), previous.streamId());
        if (readmission instanceof SseStreamCapService.Admission.Granted granted) {
            session.lease(granted.lease());
            rearmWatchdog(session);
            return true;
        }
        return false;
    }

    /**
     * Re-arms the watchdog at the lease's new deadline. Local work only: a cancel and a schedule.
     *
     * <p>Runs on the maintenance thread, so it can race a concurrent {@code close}. The loser schedules a watchdog
     * into a list that has already been removed; that task then finds no session and returns. Harmless, and bounded
     * by one scheduled no-op per race.
     */
    private void rearmWatchdog(InAppStreamSession session) {
        String streamId = session.streamId();
        List<ScheduledFuture<?>> existing = timers.get(streamId);
        if (existing == null || existing.size() < 2) {
            return;
        }
        existing.get(1).cancel(false);
        ScheduledFuture<?> watchdog = scheduler.schedule(
                () -> expire(streamId), Math.max(1, session.lease().remainingMillis(time)), TimeUnit.MILLISECONDS);
        existing.set(1, watchdog);
    }

    /**
     * The watchdog, and the reason the timer pool must stay local (finding R1).
     *
     * <p>It revokes the stream's authority to emit - a compare-and-set, with no lock and no I/O - and then returns.
     * It does <b>not</b> complete the emitter here: {@code complete()} contends for the very lock a stalled write is
     * holding, so doing it on this thread is how two stalled clients used to starve every other stream's watchdog.
     *
     * <p>What this guarantees, precisely: after the deadline the stream <b>starts no new emission</b> - no message,
     * no heartbeat comment, no {@code resync} - because every emission path checks the monotonic deadline before
     * writing. It does not interrupt a write already blocked in the container, and it makes no promise about when
     * the socket closes; that remains the accepted connector-dependent delay. The pre-write check is an independent
     * safety boundary, so this defect on its own never implied tenant-cap oversubscription.
     */
    public void expire(String streamId) {
        InAppStreamSession session = sessions.get(streamId);
        if (session == null) {
            return;
        }
        if (!session.authorityExpired()) {
            rearmWatchdog(session);
            return;
        }
        meters.counter("notify.inapp.authority_expired").increment();
        session.revoke("watchdog: serving authority expired");
        close(streamId, "authority expired");
    }

    /**
     * Closes a stream: immediate, idempotent and non-blocking here; the blocking remainder is queued.
     *
     * <p>Logical cleanup happens exactly once, owned by whichever thread wins {@code sessions.remove} - which is
     * also the thread that releases the local permit and queues the emitter completion and the Redis release. Safe
     * to call repeatedly and concurrently from the container's completion, timeout and error callbacks, from the
     * watchdog, from a heartbeat and from shutdown.
     *
     * <p>Every step here is lock-free and non-blocking: a map removal, timer cancellations, a compare-and-set, a
     * semaphore release and one submit. That matters beyond latency - Spring invokes a completion callback from
     * inside {@code ResponseBodyEmitter}, so a close that waited on the emitter's write lock could deadlock against
     * the very completion that triggered it.
     */
    public void close(String streamId, String reason) {
        InAppStreamSession session = sessions.remove(streamId);
        List<ScheduledFuture<?>> scheduled = timers.remove(streamId);
        if (scheduled != null) {
            scheduled.forEach(future -> future.cancel(false));
        }
        // Retains nothing for a stream that has gone: a queued task for it now drops when it runs.
        dispatchInFlight.remove(streamId);
        if (session == null) {
            return;                                     // another thread already owns this close
        }
        session.revoke(reason);                         // authority is gone before this method returns
        localSlots.release();
        // One reservation per closed session, covering its pending, queued and running cleanup (finding C1).
        if (cleanupSlots.tryAcquire()) {
            cleanupReserved.put(streamId, Boolean.TRUE);
            submitCleanup(session, reason, 0);
        } else {
            abandonedCleanups.incrementAndGet();
            meters.counter("notify.inapp.cleanup_abandoned").increment();
            log.warn("SSE cleanup for stream {} not started: {} cleanups are already pending ({}). The emitter will "
                            + "be completed by the async timeout and the Redis member will lapse on its lease",
                    streamId, props.queueCapacity(), reason);
        }
    }

    private boolean tenantActive(UUID tenantId) {
        return tenantStatus.getTenantStatus(tenantId).filter(TenantStatus.ACTIVE::equals).isPresent();
    }

    /**
     * Shutdown, in the one order that neither leaks nor deadlocks.
     *
     * <p>Close every stream first, which revokes authority at once and queues the completions. Then give the pools a
     * bounded moment to run that queued cleanup - bounded, because a stalled emitter's completion can wait as long
     * as the write does and shutdown may not. Whatever is still queued is abandoned: the container completes those
     * emitters as the context goes down and the Redis members lapse on their leases.
     */
    @PreDestroy
    public void shutdown() {
        for (String streamId : List.copyOf(sessions.keySet())) {
            close(streamId, "application shutdown");
        }
        for (ExecutorService pool : ownedPools) {
            pool.shutdown();
        }
        for (ExecutorService pool : ownedPools) {
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        scheduler.shutdownNow();
        // Dropping the scheduler's delayed queue discards any cleanup still waiting to retry, so its reservation is
        // returned here rather than counted as leaked (finding C1).
        for (String streamId : List.copyOf(cleanupReserved.keySet())) {
            releaseCleanupSlot(streamId);
        }
    }
}
