package com.notify.webhook.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Name resolution with a bounded caller wait and bounded resources (F7).
 *
 * <p>{@link InetAddress#getAllByName(String)} is synchronous and has no timeout: on a black-holed resolver it blocks
 * for as long as the platform's own resolver takes. Called inline it would let one attempt outlive the whole delivery
 * budget, which is the gap this class closes.
 *
 * <p><b>What this actually guarantees, and what it does not.</b> The <em>caller</em> waits at most the timeout it
 * asks for. A lookup that is still blocked when that timeout expires keeps running on this pool's thread —
 * {@code Future.cancel(true)} interrupts that thread, but a platform name lookup is not reliably interruptible, so
 * the task may continue until the OS resolver gives up. That is why the pool is small and its queue is finite rather
 * than why it is unbounded: at most {@code maxConcurrent} stalled lookups can exist at once plus
 * {@code queueCapacity} waiting, and anything beyond that is <b>rejected immediately and explicitly</b> as a
 * resolution failure instead of queueing without limit. Repeated stalls therefore cost a fixed, known amount of
 * memory and threads, and the delivery path treats saturation the way it treats any other unreachable destination:
 * retryable (), never a silent hang.
 *
 * <p>Every answer still goes through {@link IpRangePolicy} in {@link DestinationResolver}, and the request is still
 * pinned to the validated addresses, so bounding the wait changes nothing about what the policy accepts.
 */
public class BoundedDnsResolver implements HostNameResolver, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BoundedDnsResolver.class);

    private final ThreadPoolExecutor executor;
    private final int maxConcurrent;
    private final int queueCapacity;

    public BoundedDnsResolver(int maxConcurrent, int queueCapacity) {
        if (maxConcurrent < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("the webhook DNS pool needs at least one thread and one queue slot");
        }
        this.maxConcurrent = maxConcurrent;
        this.queueCapacity = queueCapacity;
        this.executor = new ThreadPoolExecutor(
                maxConcurrent, maxConcurrent,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonThreads(),
                // Reject rather than run on the caller: running it here would restore the unbounded wait this class
                // exists to remove.
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "webhook-dns-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public InetAddress[] resolve(String host, long timeoutMs) throws UnknownHostException {
        long bounded = Math.max(1L, timeoutMs);
        Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> lookup(host));
        } catch (RejectedExecutionException e) {
            // Saturation is explicit and immediate: {maxConcurrent} lookups are in flight and {queueCapacity} are
            // waiting, which in practice means the resolver is not answering. Failing here keeps the resource
            // footprint fixed.
            log.warn("Webhook DNS pool saturated ({} in flight, {} queued); refusing a new lookup",
                    maxConcurrent, queueCapacity);
            throw new UnknownHostException("name resolution is saturated; no capacity to resolve " + host);
        }
        try {
            InetAddress[] answers = future.get(bounded, TimeUnit.MILLISECONDS);
            return answers == null ? new InetAddress[0] : answers;
        } catch (TimeoutException e) {
            // Interrupt the worker. A platform lookup may ignore the interrupt and keep running; the pool's fixed
            // size is what bounds that, not this call.
            future.cancel(true);
            throw new UnknownHostException("name resolution for " + host + " exceeded " + bounded + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownHostException("name resolution for " + host + " was interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException unknownHost) {
                throw unknownHost;
            }
            throw new UnknownHostException("name resolution for " + host + " failed: "
                    + (cause == null ? e.getClass().getName() : cause.getClass().getName()));
        }
    }

    /**
     * The blocking lookup itself, on a pool thread. Overridable so a test can stall deterministically — with a latch
     * rather than a sleep — without touching real DNS; production always uses the platform resolver.
     */
    protected InetAddress[] lookup(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    /** In-flight plus queued lookups; exposed so a test can assert the bound rather than infer it. */
    public int activeLookups() {
        return executor.getActiveCount() + executor.getQueue().size();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
