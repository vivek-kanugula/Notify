package com.notify.webhook.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.webhook.model.WebhookEndpointView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3: endpoint timestamps are business time from the injected {@link Clock}, not {@code Instant.now()}.
 *
 * <p>The entity field initializers would otherwise decide {@code created_at}, which is invisible to a fixed clock and
 * untestable. With the clock pinned, every timestamp the service writes must equal it exactly.
 */
@Import(WebhookEndpointClockTest.FixedClockConfig.class)
@TestPropertySource(properties = {
        "notify.demo.webhook-url=http://localhost:9099/demo/webhook-receiver",
        "notify.webhook.demo-allowlist=localhost,127.0.0.1"
})
class WebhookEndpointClockTest extends AbstractPostgresIntegrationTest {

    static final Instant PINNED = Instant.parse("2026-10-13T08:15:30Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }

    @Autowired
    private WebhookEndpointService service;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID newTenant() {
        return tenantRepository.saveAndFlush(
                new TenantEntity("Clock", "wh-clock-" + UUID.randomUUID())).getId();
    }

    @Test
    @DisplayName("a created endpoint carries the injected clock's instant in createdAt and updatedAt")
    void createdAtComesFromTheClock() {
        UUID tenantId = newTenant();

        WebhookEndpointView view = service.upsert(tenantId, "https://receiver.example.com/hook", null).view();

        assertThat(view.createdAt()).isEqualTo(PINNED);
        assertThat(view.updatedAt()).isEqualTo(PINNED);
    }

    @Test
    @DisplayName("an updated endpoint keeps its creation time and takes the clock's instant as updatedAt")
    void updateKeepsCreatedAt() {
        UUID tenantId = newTenant();
        service.upsert(tenantId, "https://receiver.example.com/hook", null);

        WebhookEndpointView updated = service.upsert(tenantId, "https://other.example.com/hook", false).view();

        assertThat(updated.createdAt()).isEqualTo(PINNED);
        assertThat(updated.updatedAt()).isEqualTo(PINNED);
    }

    @Test
    @DisplayName("a rotation stamps updatedAt from the clock and leaves createdAt alone")
    void rotationUsesTheClock() {
        UUID tenantId = newTenant();
        service.upsert(tenantId, "https://receiver.example.com/hook", null);

        service.rotateSecret(tenantId);

        WebhookEndpointView view = service.find(tenantId).orElseThrow();
        assertThat(view.createdAt()).isEqualTo(PINNED);
        assertThat(view.updatedAt()).isEqualTo(PINNED);
    }

    @Test
    @DisplayName("a provisioned demo endpoint is stamped from the clock too")
    void provisioningUsesTheClock() {
        UUID tenantId = newTenant();

        service.provisionDefault(tenantId);

        WebhookEndpointView view = service.find(tenantId).orElseThrow();
        assertThat(view.createdAt()).isEqualTo(PINNED);
        assertThat(view.updatedAt()).isEqualTo(PINNED);
    }
}
