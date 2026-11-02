package com.notify.observability.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** / / Prometheus scrape exposes custom notify_* meters; gauges reflect the DB; no tenant_id by default. */
@AutoConfigureMetrics
class MetricsEndpointIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String adminToken() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult result = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void persistQueued(int n) {
        TenantEntity tenant = tenantRepository.save(
                new TenantEntity("Metrics Org", "metrics-" + UUID.randomUUID().toString().substring(0, 8)));
        for (int i = 0; i < n; i++) {
            NotificationEntity e = new NotificationEntity();
            e.setTenantId(tenant.getId());
            e.setChannel(Channel.EMAIL);
            e.setStatus(NotificationStatus.QUEUED);
            e.setRecipient("user@example.com");
            e.setBody("body");
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            notificationRepository.save(e);
        }
    }

    @Test
    @DisplayName("/actuator/prometheus exposes custom notify_* meters to admin, without tenant_id labels")
    void scrapeExposesNotifyMeters() throws Exception {
        String admin = adminToken();
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("notify_queue_depth")))
                .andExpect(content().string(containsString("notify_outbox_pending")))
                .andExpect(content().string(containsString("notify_dlq_open")))
                .andExpect(content().string(not(containsString("tenant_id="))));
    }

    @Test
    @DisplayName("queue-depth gauge reflects QUEUED rows in the database")
    void gaugeReflectsDatabase() {
        persistQueued(2);
        double queued = meterRegistry.get("notify.queue.depth").tag("status", "QUEUED").gauge().value();
        assertThat(queued).isGreaterThanOrEqualTo(2.0);
    }
}
