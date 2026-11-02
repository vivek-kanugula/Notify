package com.notify.delivery.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The one place a resilience decision is taken about an outbound provider call (/ , ).
 *
 * <p><b>Where this sits, and why it matters.</b> It wraps the <b>provider invocation only</b> - not
 * {@code DeliveryChannelAdapter.send}. That adapter method also contains the preparation seam, which reads
 * the database; a breaker around it would count a {@code ChannelPreparationException} - an infrastructure failure
 * before any provider request - as a provider failure and could open a breaker on a database problem. Keeping the
 * guard at the narrowest point leaves the typed preparation boundary exactly as it is.
 *
 * <p><b>Order of the three checks, and why.</b> Rate limit, then breaker, then bulkhead. A rate-limited call should
 * not consume a breaker permission; a call the breaker refuses should not occupy a bulkhead slot.
 *
 * <p><b>What is released, stated exactly.</b> A <em>breaker</em> permission is returned when the bulkhead then
 * refuses, and a <em>bulkhead</em> permit is released in a {@code finally} on success, on a classified failure and
 * on a thrown exception alike (). A <em>rate-limit</em> permission is <b>not</b> refunded if a later check
 * refuses: {@code RateLimiter} exposes no release, and a spent permission only means this instance sends slightly
 * fewer requests in that window than the limit allows - never more. The guard order keeps that case rare, and the
 * limiter is configured for one provider only.
 *
 * <p><b>How long each refusal defers for.</b> {@code RATE_LIMITED} waits the limiter's own refresh period;
 * {@code CIRCUIT_OPEN} and {@code BULKHEAD_FULL} both wait {@code breaker.wait-in-open-state}. A full bulkhead
 * usually clears sooner than that, so the wait is deliberately conservative rather than tuned: a deferral costs no
 * attempt, and the orchestrator floors every deferral at its own minimum anyway.
 *
 * <p><b>No second retry layer.</b> Nothing here retries. Resilience4j's retry module is not a dependency, and one
 * attempt means exactly one provider invocation - asserted by a test that counts them (). Retries remain
 * durable: {@code next_retry_at} plus the Quartz intent.
 *
 * <p><b>IN_APP is absent by construction.</b> It calls no provider, so no guard is wired into that path at all.
 */
public class ProviderCallGuard {

    private static final Logger log = LoggerFactory.getLogger(ProviderCallGuard.class);

    public static final String CIRCUIT_OPEN = "PROVIDER_CIRCUIT_OPEN";
    public static final String BULKHEAD_FULL = "PROVIDER_BULKHEAD_FULL";
    public static final String RATE_LIMITED = "PROVIDER_RATE_LIMITED";

    /** Every code this guard can produce. All three are decided before any provider contact. */
    private static final java.util.Set<String> LOCAL_REFUSALS =
            java.util.Set.of(CIRCUIT_OPEN, BULKHEAD_FULL, RATE_LIMITED);

    /**
     * Whether an error code is one of this guard's local refusals, taken before any provider request.
     *
     * <p>Adapters use it in {@code providerFor} so such an attempt is recorded with
     * {@code delivery_attempts.provider = 'notify-system'}, exactly like the tenant gate and the three
     * webhook destination refusals. It matters on one path: when a notification reaches
     * {@code notify.resilience.max-deferrals}, the deferral becomes an ordinary retryable failure that <em>does</em>
     * write an attempt row - and no request was sent for it, so the provider must not be named.
     */
    public static boolean isLocalRefusal(String code) {
        return code != null && LOCAL_REFUSALS.contains(code);
    }

    private final ResilienceProperties props;
    private final MeterRegistry meters;

    public ProviderCallGuard(ResilienceProperties props, MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
    }

    /**
     * Runs one provider call under the guard.
     *
     * @param breaker   the breaker for this provider or host; {@code null} skips the breaker
     * @param bulkhead  the channel's bulkhead; {@code null} skips it
     * @param limiter   the provider's outbound rate limiter; {@code null} skips it
     * @param isFailure decides whether a <em>returned</em> result counts as a provider failure for the breaker.
     *                  The adapters already classify their outcomes, so the breaker records that existing judgement
     *                  rather than inventing one - and a destination-policy refusal or a 4xx the mapper calls
     *                  permanent never opens a breaker.
     * @throws ProviderDeferredException when the call is refused locally, before any provider contact
     */
    public <T> T execute(
            String label,
            CircuitBreaker breaker,
            Bulkhead bulkhead,
            RateLimiter limiter,
            Predicate<T> isFailure,
            Supplier<T> call
    ) {
        if (!props.enabled()) {
            return call.get();
        }

        if (limiter != null && !limiter.acquirePermission()) {
            refuse(label, RATE_LIMITED, props.rateLimit().resendPeriod());
        }
        if (breaker != null && !breaker.tryAcquirePermission()) {
            refuse(label, CIRCUIT_OPEN, props.breaker().waitInOpenState());
        }
        if (bulkhead != null && !bulkhead.tryAcquirePermission()) {
            if (breaker != null) {
                breaker.releasePermission();      // the breaker permission was never spent on a call
            }
            refuse(label, BULKHEAD_FULL, props.breaker().waitInOpenState());
        }

        long startedAt = System.nanoTime();
        boolean bulkheadHeld = bulkhead != null;
        try {
            T result = call.get();
            long elapsed = System.nanoTime() - startedAt;
            if (breaker != null) {
                if (isFailure.test(result)) {
                    breaker.onError(elapsed, TimeUnit.NANOSECONDS, new ProviderOutcomeFailure(label));
                } else {
                    breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                }
            }
            return result;
        } catch (RuntimeException e) {
            if (breaker != null) {
                breaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, e);
            }
            throw e;
        } finally {
            if (bulkheadHeld) {
                bulkhead.releasePermission();
            }
        }
    }

    private void refuse(String label, String code, Duration retryAfter) {
        meters.counter("notify.resilience.deferred", "reason", code, "target", metricTarget(label)).increment();
        log.debug("Provider call to {} refused locally: {} (retry after {})", label, code, retryAfter);
        throw new ProviderDeferredException(code, retryAfter);
    }

    /**
     * The bounded form of a label, for use as a metric tag.
     *
     * <p>A webhook label carries the destination host ({@code webhook:hooks.example.com}) because an operator
     * reading a log line needs it. A host is <b>tenant-controlled and unbounded</b>, and a meter registry never
     * evicts a series, so putting it in a tag would grow cardinality without limit even though the breaker registry
     * itself is bounded. The tag therefore keeps only the part before the colon - {@code webhook} or {@code resend}
     * - which is bounded by the number of provider integrations. The host stays in the log line, which rotates.
     */
    private static String metricTarget(String label) {
        int colon = label.indexOf(':');
        return colon < 0 ? label : label.substring(0, colon);
    }

    /**
     * Marks a returned result that the adapter already classified as a transient provider failure, so the breaker
     * records it. Carries no message from the provider: a breaker signal must never become a channel for payload.
     */
    static final class ProviderOutcomeFailure extends RuntimeException {

        ProviderOutcomeFailure(String label) {
            super(label);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
