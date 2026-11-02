package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.Actor;
import com.notify.common.model.AttemptOutcome;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.entity.DeliveryAttemptEntity;
import com.notify.delivery.repository.DeliveryAttemptRepository;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminNotificationControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired private NotificationStatusHistoryRepository statusHistoryRepository;
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

    private UUID signup(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated());
        return tenantRepository.findAll().stream()
                .filter(t -> t.getName().equals(name)).map(TenantEntity::getId).findFirst().orElseThrow();
    }

    private NotificationEntity persistNotification(UUID tenantId) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.DELIVERED);
        n.setRecipient("user@example.com");
        n.setBody("secret body never surfaced");
        n.setSubject("Welcome");
        n.setMetadata("{\"campaign\":\"launch\"}");
        n.setProvider("fake");
        n.setProviderMessageId("msg-123");
        n.setAttemptCount(2);
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        n.setCompletedAt(Instant.now());
        return notificationRepository.save(n);
    }

    private void persistAttempt(UUID tenantId, UUID notificationId, int number, AttemptOutcome outcome, long latency) {
        DeliveryAttemptEntity a = new DeliveryAttemptEntity();
        a.setTenantId(tenantId);
        a.setNotificationId(notificationId);
        a.setAttemptNumber(number);
        a.setProvider("fake");
        a.setStartedAt(Instant.now());
        a.setFinishedAt(Instant.now());
        a.setOutcome(outcome);
        a.setLatencyMs(latency);
        deliveryAttemptRepository.save(a);
    }

    private void persistHistory(UUID tenantId, UUID notificationId, NotificationStatus from, NotificationStatus to) {
        NotificationStatusHistoryEntity h = new NotificationStatusHistoryEntity();
        h.setTenantId(tenantId);
        h.setNotificationId(notificationId);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setActor(Actor.SYSTEM);
        h.setOccurredAt(Instant.now());
        statusHistoryRepository.save(h);
    }

    @Test
    @DisplayName("admin notification search filters by tenant with clamped pagination")
    void search() throws Exception {
        String admin = adminToken();
        UUID tenantId = signup("ExplorerCorp");
        persistNotification(tenantId);

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .param("tenantId", tenantId.toString())
                        .param("size", "500") // clamped to 100
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.content[0].channel").value("EMAIL"));
    }

    @Test
    @DisplayName("admin notification detail returns attempts and status history, never the body")
    void detail() throws Exception {
        String admin = adminToken();
        UUID tenantId = signup("DetailCorp");
        NotificationEntity n = persistNotification(tenantId);
        persistAttempt(tenantId, n.getId(), 1, AttemptOutcome.RETRYABLE_FAILURE, 50);
        persistAttempt(tenantId, n.getId(), 2, AttemptOutcome.SUCCESS, 80);
        persistHistory(tenantId, n.getId(), NotificationStatus.QUEUED, NotificationStatus.PROCESSING);
        persistHistory(tenantId, n.getId(), NotificationStatus.PROCESSING, NotificationStatus.DELIVERED);

        mockMvc.perform(get("/api/v1/admin/notifications/" + n.getId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(n.getId().toString()))
                .andExpect(jsonPath("$.providerMessageId").value("msg-123"))
                .andExpect(jsonPath("$.deliveryAttempts.length()").value(2))
                .andExpect(jsonPath("$.deliveryAttempts[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.deliveryAttempts[1].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.statusHistory.length()").value(2))
                .andExpect(jsonPath("$.statusHistory[1].toStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    @DisplayName("admin notification detail returns 404 for unknown id")
    void detailNotFound() throws Exception {
        String admin = adminToken();
        mockMvc.perform(get("/api/v1/admin/notifications/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }
}
