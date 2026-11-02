package com.notify.sse.stream;

import com.notify.inapp.model.InAppMessageView;
import com.notify.inapp.service.InAppMessageService;
import com.notify.sse.config.SseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One live SSE stream (/ , , ).
 *
 * <p><b>The drain loop.</b> A Redis hint, a heartbeat tick, a re-subscribe or the stream opening all do the same
 * thing: set {@code dirty} and signal. A single drain then reads authoritative rows from PostgreSQL in {@code seq}
 * ascending order above the cursor, emits them in that order, and advances the cursor <b>only across rows it
 * actually read and sent</b>. Nothing is ever taken from a hint — not ordering, not a sequence, not a payload — so
 * duplicate, reordered and lost hints are all either no-ops or one extra drain.
 *
 * <p><b>Why that is lossless.</b> The writer holds a per-recipient advisory lock from sequence allocation to commit
 * (), so for one recipient commit order equals {@code seq} order. A row with a lower sequence can therefore
 * never commit after a higher one, and a cursor moving only across rows it has read can never step over anything.
 *
 * <p><b>resync</b> is emitted when the bounded read returns a {@code limit + 1}-th row: the decision is made on
 * actual eligible rows, never on arithmetic over sequence values, because the global sequence is sparse by design.
 *
 * <p><b>Stopping.</b> Every emission first checks the monotonic serving deadline. A process frozen by GC, a
 * hypervisor suspend or a container pause therefore cannot produce a new emission after its authority lapsed, even
 * though no timer fired while it was stopped. This pre-write check — not the watchdog, and not any ability to
 * interrupt a blocked write — is what the stream cap rests on.
 */
public class InAppStreamSession {

    private static final Logger log = LoggerFactory.getLogger(InAppStreamSession.class);

    /**
     * Why a stream told its client to fall back to the paginated read.
     *
     * <p>Only {@code GAP_TOO_LARGE} is emitted today. The others name conditions the stream can reach but does not
     * announce in band: a lost lease or a deactivated tenant closes the connection from the timer thread without
     * writing (see {@code SseStreamRegistry#runHeartbeat}), because the client learns the reason and the retry
     * interval readably from its next token request instead.
     */
    public enum ResyncReason {
        GAP_TOO_LARGE, BUFFER_OVERFLOW, SLOW_CLIENT, LEASE_LOST, TENANT_NOT_ACTIVE
    }

    private final SseEmitter emitter;
    private final InAppMessageService messages;
    private final SseProperties props;
    private final MonotonicTimeSource time;
    private final ReentrantLock drainLock = new ReentrantLock();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile SseStreamLease lease;
    private volatile long cursor;
    private volatile long emitted;

    public InAppStreamSession(SseEmitter emitter, SseStreamLease lease, long startCursor,
                              InAppMessageService messages, SseProperties props, MonotonicTimeSource time) {
        this.emitter = emitter;
        this.lease = lease;
        this.cursor = startCursor;
        this.messages = messages;
        this.props = props;
        this.time = time;
    }

    public SseStreamLease lease() {
        return lease;
    }

    public void lease(SseStreamLease updated) {
        this.lease = updated;
    }

    public String streamId() {
        return lease.streamId();
    }

    public long cursor() {
        return cursor;
    }

    public long emittedCount() {
        return emitted;
    }

    public boolean isStopped() {
        return stopped.get();
    }

    /** True once the local serving deadline has passed: no new emission may be produced. */
    public boolean authorityExpired() {
        return lease.expired(time);
    }

    public void markDirty() {
        dirty.set(true);
    }

    /**
     * Drains until nothing new is pending. Single-flight: two concurrent drains could interleave emissions and
     * corrupt the cursor, so the second caller simply leaves — the first will observe the flag it set.
     */
    public void drain() {
        if (!drainLock.tryLock()) {
            return;
        }
        try {
            while (dirty.getAndSet(false) && !stopped.get()) {
                if (!drainOnce()) {
                    return;
                }
            }
        } finally {
            drainLock.unlock();
        }
    }

    /** @return false when the stream stopped during this pass. */
    private boolean drainOnce() {
        if (stopWhenAuthorityExpired()) {
            return false;
        }
        int limit = props.catchUpLimitProbe();
        List<InAppMessageView> rows = messages.ascendingAbove(
                lease.tenantId(), lease.recipientRef(), cursor, limit);
        if (rows.size() > props.catchUpLimit()) {
            // More eligible rows than the catch-up budget: tell the client to page, do not truncate the sequence.
            resync(ResyncReason.GAP_TOO_LARGE);
            return false;
        }
        for (InAppMessageView row : rows) {
            if (stopWhenAuthorityExpired()) {
                return false;
            }
            if (!emit(row)) {
                return false;
            }
            cursor = row.seq();
            emitted++;
        }
        return true;
    }

    private boolean emit(InAppMessageView row) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(row.seq()))
                    .name("message")
                    .data(Map.of(
                            "seq", Long.toString(row.seq()),
                            "notificationId", row.notificationId().toString(),
                            "subject", row.subject() == null ? "" : row.subject(),
                            "body", row.body() == null ? "" : row.body(),
                            "read", row.readAt() != null,
                            "createdAt", row.createdAt() == null ? "" : row.createdAt().toString())));
            return true;
        } catch (IOException | IllegalStateException e) {
            // The client is gone, or the response is no longer usable. Nothing to repair: the cursor was not moved,
            // so a reconnect replays from the last row actually sent.
            stop("client gone: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Heartbeat comment plus a drain. Returns false when the stream has stopped. */
    public boolean heartbeat() {
        if (stopWhenAuthorityExpired()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException e) {
            stop("heartbeat failed: " + e.getClass().getSimpleName());
            return false;
        }
        markDirty();
        drain();
        return !stopped.get();
    }

    /**
     * Tells the client to fall back to the paginated read, then stops.
     *
     * <p>A {@code resync} is an emission like any other, so it takes the same pre-write check (corrective cycle):
     * a stream whose serving authority has lapsed writes nothing at all, not even this frame. The client learns the
     * same thing from the connection ending and from the admission answer on its next token request.
     */
    public void resync(ResyncReason reason) {
        if (stopWhenAuthorityExpired()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("resync")
                    .data(Map.of("reason", reason.name(), "fromCursor", Long.toString(cursor))));
        } catch (IOException | IllegalStateException e) {
            log.debug("resync could not be sent ({})", e.getClass().getSimpleName());
        }
        stop("resync:" + reason);
    }

    /** The pre-write check. Returns true when the stream must not produce another emission. */
    private boolean stopWhenAuthorityExpired() {
        if (stopped.get()) {
            return true;
        }
        if (authorityExpired()) {
            stop("serving authority expired");
            return true;
        }
        return false;
    }

    /**
     * Revokes this stream's local emission authority. <b>Immediate, idempotent and local</b>: it touches no socket,
     * no lock of the emitter and no network.
     *
     * <p>Split out from {@link #releaseEmitter()} because of a measured property of the resolved Spring 7.0.9
     * runtime: {@code ResponseBodyEmitter.send(Set)} and {@code ResponseBodyEmitter.complete()} take the <b>same</b>
     * {@code writeLock}, so completing an emitter whose write is stalled blocks for as long as that write does. The
     * watchdog must be able to revoke authority at its deadline without waiting for anything, so it calls this and
     * hands the completion to a bounded pool.
     *
     * @return true when this call was the one that revoked it
     */
    public boolean revoke(String reason) {
        if (stopped.compareAndSet(false, true)) {
            log.debug("SSE stream {} authority revoked: {}", lease.streamId(), reason);
            return true;
        }
        return false;
    }

    /**
     * Completes the emitter. <b>May block</b> for as long as a stalled write holds the emitter's write lock - up to
     * the container's own cleanup of that write, measured at about 59 s on this stack. Call it only from a pool that
     * is allowed to block; never from the timer pool or a Redis listener callback.
     *
     * <p>Idempotent in effect: a second call, or a call after the container already completed the request, throws
     * inside Spring and is swallowed here.
     */
    public void releaseEmitter() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("emitter.complete() threw ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * Revokes authority and completes the emitter in one step.
     *
     * <p>Only for callers already running on a pool that may block - the dispatcher's own emission paths. The timer
     * pool uses {@link #revoke(String)} instead.
     */
    public void stop(String reason) {
        if (revoke(reason)) {
            releaseEmitter();
        }
    }
}
