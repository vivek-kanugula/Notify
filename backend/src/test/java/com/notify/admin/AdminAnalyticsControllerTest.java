package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
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

class AdminAnalyticsControllerTest extends AbstractPostgresIntegrationTest {

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

    private String adminToken() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult result = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private record TenantFixture(UUID tenantId, String token) {}

    private TenantFixture signup(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        TenantEntity entity = tenantRepository.findAll().stream()
                .filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
        return new TenantFixture(entity.getId(), token);
    }

    private void persist(UUID tenantId, Channel channel, NotificationStatus st, String errorCode,
                         Instant createdAt, Instant completedAt) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(channel);
        n.setStatus(st);
        n.setRecipient("user@example.com");
        n.setBody("rendered body");
        n.setSubject("Subject");
        n.setLastErrorCode(errorCode);
        n.setCreatedAt(createdAt);
        n.setUpdatedAt(createdAt);
        n.setCompletedAt(completedAt);
        notificationRepository.save(n);
    }

    @Test
    @DisplayName("admin overview rolls up counts, success rate, queue depth, failures")
    void overview() throws Exception {
        String admin = adminToken();
        TenantFixture a = signup("OverviewAcme");
        TenantFixture b = signup("OverviewGlobex");
        // Isolated historical window so other suites' "now" fixtures in the shared DB never leak into the counts.
        Instant base = Instant.parse("2023-01-15T10:00:00Z");
        String from = "2023-01-01T00:00:00Z";
        String to = "2023-02-01T00:00:00Z";

        // delivered with measurable latency (completed 100ms after created)
        persist(a.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, null, base, base.plusMillis(100));
        persist(a.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, null, base, base.plusMillis(100));
        persist(a.tenantId(), Channel.EMAIL, NotificationStatus.FAILED, "EMAIL_REJECTED", base, base);
        persist(b.tenantId(), Channel.WEBHOOK, NotificationStatus.DLQ, "WEBHOOK_TIMEOUT", base, null);
        persist(b.tenantId(), Channel.WEBHOOK, NotificationStatus.QUEUED, null, base, null);

        mockMvc.perform(get("/api/v1/admin/analytics/overview")
                        .param("from", from).param("to", to)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalNotifications").value(5))
                .andExpect(jsonPath("$.deliveredCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(2)) // FAILED + DLQ
                .andExpect(jsonPath("$.activeCount").value(1))  // QUEUED (windowed)
                // queueDepth/dlqOpenCount are point-in-time global (not windowed); assert >= our seeded rows
                // so the shared-DB cumulative gate cannot make them flaky.
                .andExpect(jsonPath("$.queueDepth").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.dlqOpenCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.latencyPercentilesMs.p50").value(100))
                .andExpect(jsonPath("$.topTenants").isArray())
                .andExpect(jsonPath("$.topFailureReasons").isArray());
    }

    @Test
    @DisplayName("admin analytics rejects a tenant token")
    void boundaryRejectsTenant() throws Exception {
        TenantFixture t = signup("BoundaryCorp");
        mockMvc.perform(get("/api/v1/admin/analytics/overview").header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin per-tenant usage returns summary; unknown tenant 404")
    void perTenantUsage() throws Exception {
        String admin = adminToken();
        TenantFixture a = signup("PerTenantCorp");
        persist(a.tenantId(), Channel.EMAIL, NotificationStatus.DELIVERED, null, Instant.now(), Instant.now());

        mockMvc.perform(get("/api/v1/admin/tenants/" + a.tenantId() + "/usage")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDelivered").value(1));

        mockMvc.perform(get("/api/v1/admin/tenants/" + UUID.randomUUID() + "/usage")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }
}
