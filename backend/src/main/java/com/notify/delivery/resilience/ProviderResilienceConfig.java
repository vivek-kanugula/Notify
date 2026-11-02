package com.notify.delivery.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Programmatic Resilience4j wiring (/ , decision ).
 *
 * <p><b>Core modules only, configured here and nowhere else.</b> No {@code resilience4j-spring-boot3} starter, no
 * annotations, no auto-configuration: this Spring Boot 4 / Framework 7 application constructs every primitive
 * itself, so nothing depends on a Boot-3 adapter and the configuration is visible in one file. Only the three
 * modules actually used are on the classpath - circuitbreaker, bulkhead and ratelimiter - and
 * {@code resilience4j-timelimiter} is deliberately absent, because the existing Resend budget
 * (connect + read, with a bounded body reader) and {@code WebhookDeadline} already bound every outbound call.
 *
 * <p>Every bean here exists whether or not {@code notify.resilience.enabled} is set; the guard itself short-circuits
 * when it is off, so turning the feature on or off never changes which beans exist.
 */
@Configuration(proxyBeanMethods = false)
public class ProviderResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderResilienceConfig.class);

    /** The breaker shape shared by the Resend breaker and every per-host webhook breaker. */
    @Bean
    public CircuitBreakerConfig providerCircuitBreakerConfig(ResilienceProperties props) {
        ResilienceProperties.Breaker breaker = props.breaker();
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(breaker.failureRateThresholdPercent())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumCalls())
                .waitDurationInOpenState(breaker.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.halfOpenPermittedCalls())
                // Without this, a breaker only leaves the open state when a call arrives, so a tenant with no
                // traffic would stay deferred indefinitely after the wait elapsed.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    @Bean
    public ProviderCallGuard providerCallGuard(
            ResilienceProperties props, ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable(SimpleMeterRegistry::new);
        if (props.enabled()) {
            log.info("Provider resilience is ENABLED: breaker {}% over {} calls, {} in open state; bulkheads "
                            + "email={} webhook={}; resend rate limit {}/{} per instance; host registry max {}",
                    props.breaker().failureRateThresholdPercent(), props.breaker().slidingWindowSize(),
                    props.breaker().waitInOpenState(), props.bulkhead().email(), props.bulkhead().webhook(),
                    props.rateLimit().resendPermits(), props.rateLimit().resendPeriod(),
                    props.hostRegistry().maxHosts());
            if (!props.idleExpiryOutlivesAnOpenBreaker()) {
                throw new IllegalStateException(
                        "notify.resilience.host-registry.idle-expiry must exceed breaker.wait-in-open-state, or an "
                                + "idle host's open breaker could be evicted and start closed again");
            }
        }
        return new ProviderCallGuard(props, registry);
    }

    /** One breaker for the Resend provider: a single remote dependency, so a single key. */
    @Bean
    public CircuitBreaker resendCircuitBreaker(CircuitBreakerConfig config) {
        return CircuitBreaker.of("resend", config);
    }

    @Bean
    public HostBreakerRegistry webhookHostBreakers(
            CircuitBreakerConfig config, ResilienceProperties props, Clock clock) {
        return new HostBreakerRegistry(config, props.hostRegistry(), clock);
    }

    /**
     * Resilience state an operator can see, as metrics only.
     *
     * <p>Per the playbook, infrastructure state is exposed through metrics and system status, <b>not</b> a frontend
     * chart, so this phase adds no UI. Cardinality is bounded by construction: one gauge per channel bulkhead, one
     * for the Resend breaker, and a <em>count</em> of webhook hosts rather than a series per host - the registry is
     * bounded, but 256 host-labelled series would still be a poor trade for the same information.
     *
     * <p>No secret, recipient, body or provider payload appears in any of these: the names and the one label are
     * fixed strings, and the values are numbers.
     */
    @Bean
    public io.micrometer.core.instrument.binder.MeterBinder providerResilienceMetrics(
            CircuitBreaker resendBreaker,
            HostBreakerRegistry hostBreakers,
            @org.springframework.beans.factory.annotation.Qualifier("emailBulkhead")
            io.github.resilience4j.bulkhead.Bulkhead emailBulkhead,
            @org.springframework.beans.factory.annotation.Qualifier("webhookBulkhead")
            io.github.resilience4j.bulkhead.Bulkhead webhookBulkhead
    ) {
        return registry -> {
            // 0 closed, 1 half-open, 2 open: an ordered scale, so an alert can fire on "not closed".
            io.micrometer.core.instrument.Gauge
                    .builder("notify.resilience.breaker_state", resendBreaker,
                            breaker -> switch (breaker.getState()) {
                                case CLOSED, METRICS_ONLY -> 0;
                                case HALF_OPEN -> 1;
                                default -> 2;
                            })
                    .tag("target", "resend")
                    .register(registry);
            io.micrometer.core.instrument.Gauge
                    .builder("notify.resilience.host_breakers", hostBreakers, HostBreakerRegistry::size)
                    .register(registry);
            io.micrometer.core.instrument.Gauge
                    .builder("notify.resilience.bulkhead_available", emailBulkhead,
                            bulkhead -> bulkhead.getMetrics().getAvailableConcurrentCalls())
                    .tag("channel", "email")
                    .register(registry);
            io.micrometer.core.instrument.Gauge
                    .builder("notify.resilience.bulkhead_available", webhookBulkhead,
                            bulkhead -> bulkhead.getMetrics().getAvailableConcurrentCalls())
                    .tag("channel", "webhook")
                    .register(registry);
        };
    }

    @Bean
    public Bulkhead emailBulkhead(ResilienceProperties props) {
        return channelBulkhead("email", props.bulkhead().email());
    }

    @Bean
    public Bulkhead webhookBulkhead(ResilienceProperties props) {
        return channelBulkhead("webhook", props.bulkhead().webhook());
    }

    /**
     * A channel bulkhead. {@code maxWaitDuration} is zero on purpose: a full bulkhead must refuse <b>now</b> so the
     * notification is deferred durably, never park a worker thread waiting for a slot.
     */
    private static Bulkhead channelBulkhead(String channel, int maxConcurrentCalls) {
        return Bulkhead.of(channel, BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    /**
     * The Resend outbound rate limiter, <b>per instance</b>. {@code timeoutDuration} is zero for the same reason as
     * the bulkhead: refuse immediately and defer durably rather than block. Returns {@code null} when disabled,
     * which the guard treats as "no limiter".
     */
    @Bean
    public RateLimiter resendRateLimiter(ResilienceProperties props) {
        ResilienceProperties.RateLimit limit = props.rateLimit();
        if (!limit.limitsResend()) {
            return null;
        }
        return RateLimiter.of("resend", RateLimiterConfig.custom()
                .limitForPeriod(limit.resendPermits())
                .limitRefreshPeriod(limit.resendPeriod())
                .timeoutDuration(Duration.ZERO)
                .build());
    }
}
