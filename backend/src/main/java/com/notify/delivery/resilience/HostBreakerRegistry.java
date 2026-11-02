package com.notify.delivery.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Circuit breakers keyed by webhook <b>host</b>, in a bounded registry (/ , decision ).
 *
 * <p><b>Why per host, and what that shares.</b> The failing resource is the host, not the tenant, so two tenants
 * with endpoints on the same host share one breaker. While it is open, both tenants' deliveries to that host are
 * deferred; each deferral increments {@code deferral_count}, and a notification that reaches
 * {@code notify.resilience.max-deferrals} falls back to an ordinary retryable failure, which <b>does</b> consume an
 * attempt. So one tenant's failing host can, in the limit, cost another tenant's notification an attempt and
 * ultimately a FAILED outcome. What is never shared: any tenant's data, status, audit history, secret or endpoint
 * configuration. Distinct hosts stay fully isolated.
 *
 * <p><b>Why bounded.</b> Destinations are tenant-controlled, so the set of hosts is unbounded input. The registry
 * therefore holds at most {@code max-hosts} breakers, evicting the least recently used, and drops entries idle
 * longer than {@code idle-expiry}. That also bounds the metric cardinality derived from it. Resilience4j's own
 * registry is deliberately not used for this: it grows without limit.
 *
 * <p><b>The one ordering rule that matters.</b> An evicted breaker starts closed again, so eviction must never
 * discard a breaker that could still be open - that would quietly readmit traffic to a host already found to be
 * failing. {@code idle-expiry} is therefore required to exceed {@code wait-in-open-state}, and idle eviction is
 * additionally skipped while a breaker is not closed.
 */
public class HostBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(HostBreakerRegistry.class);

    private final CircuitBreakerConfig config;
    private final ResilienceProperties.HostRegistry limits;
    private final Clock clock;

    /** Access-ordered, so the first entry is the least recently used. Guarded by {@code this}. */
    private final LinkedHashMap<String, Entry> breakers = new LinkedHashMap<>(16, 0.75f, true);

    private record Entry(CircuitBreaker breaker, Instant[] lastUsed) {
    }

    public HostBreakerRegistry(CircuitBreakerConfig config, ResilienceProperties.HostRegistry limits, Clock clock) {
        this.config = config;
        this.limits = limits;
        this.clock = clock;
    }

    /** The breaker for one host, creating it if needed and evicting as required to stay inside the bound. */
    public synchronized CircuitBreaker forHost(String host) {
        Instant now = clock.instant();
        expireIdle(now);
        Entry existing = breakers.get(host);
        if (existing != null) {
            existing.lastUsed()[0] = now;
            return existing.breaker();
        }
        while (breakers.size() >= limits.maxHosts()) {
            if (!evictLeastRecentlyUsed()) {
                // Every breaker is open or half-open: none may be discarded, because an evicted breaker starts
                // closed and would readmit traffic to a failing host. Serve this host without a breaker rather
                // than lie about another host's state.
                log.warn("Webhook breaker registry is full at {} hosts and every entry is still open; {} proceeds "
                        + "without a breaker this attempt", limits.maxHosts(), host);
                return CircuitBreaker.of("transient:" + host, config);
            }
        }
        CircuitBreaker created = CircuitBreaker.of(host, config);
        breakers.put(host, new Entry(created, new Instant[]{now}));
        return created;
    }

    /** @return false when nothing could be evicted because no entry is closed */
    private boolean evictLeastRecentlyUsed() {
        for (Iterator<Map.Entry<String, Entry>> it = breakers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> candidate = it.next();
            if (candidate.getValue().breaker().getState() == CircuitBreaker.State.CLOSED) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void expireIdle(Instant now) {
        Duration idleExpiry = limits.idleExpiry();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : breakers.entrySet()) {
            boolean closed = entry.getValue().breaker().getState() == CircuitBreaker.State.CLOSED;
            boolean idle = Duration.between(entry.getValue().lastUsed()[0], now).compareTo(idleExpiry) > 0;
            if (closed && idle) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(breakers::remove);
    }

    public synchronized int size() {
        return breakers.size();
    }

    /** For metrics and tests: the hosts currently held, in least-recently-used order. */
    public synchronized List<String> hosts() {
        return List.copyOf(breakers.keySet());
    }
}
