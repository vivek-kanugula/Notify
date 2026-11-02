package com.notify.delivery.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resilience settings, their accepted defaults and the one relationship between them (/ , ).
 *
 * <p>Two things this test deliberately does <b>not</b> assert, because withdrew them: that a bulkhead must be at
 * least the channel's listener concurrency - a smaller positive provider limit is legitimate - and that the Resend
 * rate-limit default is provider-safe, which is unverified against Resend's published or account limits.
 */
class ResiliencePropertiesTest {

    private static ResilienceProperties production() {
        return new ResilienceProperties(
                false, 50,
                new ResilienceProperties.Breaker(50, 20, 10, Duration.ofSeconds(30), 3),
                new ResilienceProperties.Bulkhead(3, 3),
                new ResilienceProperties.RateLimit(10, Duration.ofSeconds(1)),
                new ResilienceProperties.HostRegistry(256, Duration.ofMinutes(30)),
                Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("the accepted defaults, with resilience off until it is switched on")
    void acceptedDefaults() {
        ResilienceProperties props = production();

        assertThat(props.enabled()).as("off by default, like every worker in this project").isFalse();
        assertThat(props.maxDeferrals()).as("unchanged meaning and default").isEqualTo(50);
        assertThat(props.breaker().failureRateThresholdPercent()).isEqualTo(50);
        assertThat(props.breaker().slidingWindowSize()).isEqualTo(20);
        assertThat(props.breaker().minimumCalls()).isEqualTo(10);
        assertThat(props.breaker().waitInOpenState()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.breaker().halfOpenPermittedCalls()).isEqualTo(3);
        assertThat(props.bulkhead().email()).isEqualTo(3);
        assertThat(props.bulkhead().webhook()).isEqualTo(3);
        assertThat(props.rateLimit().resendPermits()).isEqualTo(10);
        assertThat(props.rateLimit().resendPeriod()).isEqualTo(Duration.ofSeconds(1));
        assertThat(props.hostRegistry().maxHosts()).isEqualTo(256);
        assertThat(props.hostRegistry().idleExpiry()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.maxRetryAfter()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("idle expiry must outlive an open breaker, or an evicted host would start closed again")
    void idleExpiryOutlivesAnOpenBreaker() {
        assertThat(production().idleExpiryOutlivesAnOpenBreaker()).isTrue();

        ResilienceProperties tooShort = new ResilienceProperties(
                true, 50,
                new ResilienceProperties.Breaker(50, 20, 10, Duration.ofMinutes(30), 3),
                new ResilienceProperties.Bulkhead(3, 3),
                new ResilienceProperties.RateLimit(10, Duration.ofSeconds(1)),
                new ResilienceProperties.HostRegistry(256, Duration.ofMinutes(5)),
                Duration.ofMinutes(5));
        assertThat(tooShort.idleExpiryOutlivesAnOpenBreaker())
                .as("the configuration bean refuses this combination at startup")
                .isFalse();
    }

    @Test
    @DisplayName("a rate limit of zero permits disables the limiter rather than blocking every call")
    void zeroPermitsDisablesTheLimiter() {
        ResilienceProperties.RateLimit off = new ResilienceProperties.RateLimit(0, Duration.ofSeconds(1));
        assertThat(off.limitsResend()).isFalse();
        assertThat(new ResilienceProperties.RateLimit(1, Duration.ofSeconds(1)).limitsResend()).isTrue();
    }

    @Test
    @DisplayName("each bound is validated independently")
    void boundsAreValidated() {
        assertThatThrownBy(() -> new ResilienceProperties.Breaker(0, 20, 10, Duration.ofSeconds(30), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failure-rate-threshold");
        assertThatThrownBy(() -> new ResilienceProperties.Breaker(50, 2, 1, Duration.ofSeconds(30), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sliding-window-size");
        assertThatThrownBy(() -> new ResilienceProperties.Breaker(50, 20, 25, Duration.ofSeconds(30), 3))
                .as("a minimum above the window would mean the breaker could never decide")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minimum-calls");
        assertThatThrownBy(() -> new ResilienceProperties.Breaker(50, 20, 10, Duration.ZERO, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wait-in-open-state");
        assertThatThrownBy(() -> new ResilienceProperties.Bulkhead(0, 3))
                .as("a zero limit would defer every call")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bulkhead");
        assertThatThrownBy(() -> new ResilienceProperties.RateLimit(-1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resend-permits");
        assertThatThrownBy(() -> new ResilienceProperties.HostRegistry(4, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-hosts");
    }

    @Test
    @DisplayName("a smaller provider limit than the channel's listener concurrency is legitimate, not an error")
    void aSmallProviderLimitIsAllowed() {
        // withdrew the "bulkhead must be at least listener concurrency" constraint: a provider may genuinely
        // permit fewer concurrent calls than the worker has threads, and the result is an intentional local
        // deferral, which consumes no attempt.
        ResilienceProperties.Bulkhead smaller = new ResilienceProperties.Bulkhead(1, 1);
        assertThat(smaller.email()).isEqualTo(1);
        assertThat(smaller.webhook()).isEqualTo(1);
    }
}
