package com.notify.delivery.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one configuration relationship that must stop startup (/ , ; repair of review finding F4).
 *
 * <p>An evicted breaker starts <b>closed</b>, so evicting an idle host whose breaker could still be open would
 * quietly readmit traffic to a host already found to be failing. {@code host-registry.idle-expiry} must therefore
 * exceed {@code breaker.wait-in-open-state}. {@link ResiliencePropertiesTest} pins the predicate; this pins the
 * consequence - the context refuses to come up - which was previously unexecuted code.
 *
 * <p>A narrow {@link ApplicationContextRunner} rather than a {@code @SpringBootTest}: the rule lives entirely in
 * {@link ProviderResilienceConfig}, and a failed startup is cheaper and clearer to assert in isolation.
 */
class ProviderResilienceConfigStartupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ResilienceProperties.class)
    static class Harness {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Harness.class, ProviderResilienceConfig.class);

    @Test
    @DisplayName("an idle expiry that does not outlive an open breaker fails startup")
    void anIdleExpiryShorterThanTheOpenWaitFailsStartup() {
        runner.withPropertyValues(
                        "notify.resilience.enabled=true",
                        "notify.resilience.breaker.wait-in-open-state=60s",
                        "notify.resilience.host-registry.idle-expiry=30s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getCause())
                            .hasMessageContaining("idle-expiry must exceed breaker.wait-in-open-state");
                });
    }

    @Test
    @DisplayName("an equal idle expiry also fails: it must outlive the open state, not match it")
    void anEqualIdleExpiryFailsStartup() {
        runner.withPropertyValues(
                        "notify.resilience.enabled=true",
                        "notify.resilience.breaker.wait-in-open-state=30s",
                        "notify.resilience.host-registry.idle-expiry=30s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("the accepted defaults start, and the guard plus both bulkheads exist")
    void theAcceptedDefaultsStart() {
        runner.withPropertyValues("notify.resilience.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderCallGuard.class);
            assertThat(context).hasSingleBean(HostBreakerRegistry.class);
            assertThat(context.getBean("emailBulkhead", io.github.resilience4j.bulkhead.Bulkhead.class)
                    .getMetrics().getMaxAllowedConcurrentCalls()).isEqualTo(3);
            assertThat(context.getBean("webhookBulkhead", io.github.resilience4j.bulkhead.Bulkhead.class)
                    .getMetrics().getMaxAllowedConcurrentCalls()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a misconfigured relationship is only enforced when the feature is on")
    void aDisabledFeatureDoesNotEnforceTheRelationship() {
        runner.withPropertyValues(
                        "notify.resilience.enabled=false",
                        "notify.resilience.breaker.wait-in-open-state=60s",
                        "notify.resilience.host-registry.idle-expiry=30s")
                .run(context -> assertThat(context)
                        .as("a deployment that never switches resilience on is not blocked by its settings")
                        .hasNotFailed());
    }

    /** Guards the import list above: without the binding post-processor the properties would silently be defaults. */
    @Test
    @DisplayName("the harness really binds notify.resilience.* properties")
    void theHarnessBindsProperties() {
        runner.withPropertyValues("notify.resilience.bulkhead.email=7").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConfigurationPropertiesBindingPostProcessor.class);
            assertThat(context.getBean(ResilienceProperties.class).bulkhead().email()).isEqualTo(7);
        });
    }
}
