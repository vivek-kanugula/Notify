package com.notify.common.db;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Thread-bound selector for the database pool (/ ).
 *
 * <p>While a scope is open on a thread, every connection that thread acquires through the routing
 * {@link PoolRoutingDataSource} comes from the dedicated worker pool. Hibernate acquires the transaction
 * connection when the transaction begins, so the scope must be entered <em>before</em> the first
 * transactional call and left only after the outermost one has completed. {@link #enter()} therefore
 * refuses to start inside an active transaction. Always use try-with-resources.
 */
public final class WorkerPoolScope implements AutoCloseable {

    /** Routing keys of {@link PoolRoutingDataSource}. */
    public enum PoolKey {
        PRIMARY,
        WORKER
    }

    private static final ThreadLocal<PoolKey> CURRENT = new ThreadLocal<>();

    private final PoolKey previous;
    private boolean closed;

    private WorkerPoolScope(PoolKey previous) {
        this.previous = previous;
    }

    /**
     * Routes the current thread's database access to the worker pool until the returned scope is closed.
     *
     * @throws IllegalStateException if a transaction is already active on this thread (its connection was
     *                               already chosen, so the scope would silently have no effect)
     */
    public static WorkerPoolScope enter() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "WorkerPoolScope must be entered before a transaction starts; a transaction is already active");
        }
        PoolKey previous = CURRENT.get();
        CURRENT.set(PoolKey.WORKER);
        return new WorkerPoolScope(previous);
    }

    /** The pool selected for the current thread, or {@code null} when no scope is open (primary pool). */
    static PoolKey currentKey() {
        return CURRENT.get();
    }

    /** True when the current thread is inside a worker scope. */
    public static boolean isActive() {
        return CURRENT.get() == PoolKey.WORKER;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
