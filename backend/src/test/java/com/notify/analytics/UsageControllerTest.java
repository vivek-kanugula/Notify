package com.notify.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UsageControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TenantRepository tenantRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record TenantFixture(UUID tenantId, String token) {}

    private TenantFixture signup(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        TenantEntity entity = tenantRepository.findAll().stream()
                .filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
        return new TenantFixture(entity.getId(), token);
    }

    private void persist(UUID tenantId, Channel channel, NotificationStatus st, Instant createdAt, Instant completedAt) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(channel);
        n.setStatus(st);
        n.setRecipient("user@example.com");
        n.setBody("rendered body not exposed by analytics");
        n.setSubject("Subject");
        n.setCreatedAt(createdAt);
        n.setUpdatedAt(createdAt);
        n.setCompletedAt(completedAt);
        notificationRepository.save(n);
    }

    @Test
    @DisplayName("usage summary matches fixture counts and success rate")
    void usageSummary() throws Exception {
        TenantFixture t = signup("UsageCorp");
        Instant now = Instant.now();
        // 3 delivered EMAIL, 1 failed EMAIL, 1 cancelled WEBHOOK, 1 delivered WEBHOOK
        persist(t.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, now.minus(1, ChronoUnit.DAYS), now);
        persist(t.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, now.minus(2, ChronoUnit.DAYS), now);
        persist(t.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, now.minus(3, ChronoUnit.DAYS), now);
        persist(t.tenantId(), Channel.EMAIL, NotificationStatus.FAILED, now.minus(1, ChronoUnit.DAYS), now);
        persist(t.tenantId(), Channel.WEBHOOK, NotificationStatus.CANCELLED, now.minus(1, ChronoUnit.DAYS), now);
        persist(t.tenantId(), Channel.WEBHOOK, NotificationStatus.DELIVERED, now.minus(1, ChronoUnit.DAYS), now);

        mockMvc.perform(get("/api/v1/usage/summary").header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccepted").value(6))
                .andExpect(jsonPath("$.totalDelivered").value(4))
                .andExpect(jsonPath("$.totalFailed").value(1))
                .andExpect(jsonPath("$.totalCancelled").value(1))
                .andExpect(jsonPath("$.deliverySuccessRate").value(0.8))
                .andExpect(jsonPath("$.byChannel.EMAIL.accepted").value(4))
                .andExpect(jsonPath("$.byChannel.EMAIL.delivered").value(3))
                .andExpect(jsonPath("$.byChannel.EMAIL.failed").value(1))
                .andExpect(jsonPath("$.byChannel.WEBHOOK.delivered").value(1));
    }

    @Test
    @DisplayName("zero traffic yields 0.0 success rate (no divide-by-zero)")
    void usageSummaryEmpty() throws Exception {
        TenantFixture t = signup("EmptyCorp");
        mockMvc.perform(get("/api/v1/usage/summary").header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccepted").value(0))
                .andExpect(jsonPath("$.deliverySuccessRate").value(0.0));
    }

    @Test
    @DisplayName("daily timeseries buckets deliveries and failures")
    void usageTimeseries() throws Exception {
        TenantFixture t = signup("SeriesCorp");
        Instant now = Instant.now();
        persist(t.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, now.minus(1, ChronoUnit.DAYS), now);
        persist(t.tenantId(), Channel.EMAIL, NotificationStatus.FAILED, now.minus(1, ChronoUnit.DAYS), now);

        mockMvc.perform(get("/api/v1/usage/timeseries")
                        .param("interval", "day")
                        .header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("day"))
                .andExpect(jsonPath("$.buckets").isArray())
                .andExpect(jsonPath("$.buckets[0].delivered").value(1))
                .andExpect(jsonPath("$.buckets[0].failed").value(1));
    }

    @Test
    @DisplayName("usage endpoints reject unauthenticated callers")
    void usageRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/usage/summary"))
                .andExpect(status().isUnauthorized());
    }
}
