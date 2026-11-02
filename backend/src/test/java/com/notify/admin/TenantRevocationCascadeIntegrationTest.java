package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.admin.dto.UpdateTenantStatusRequest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.apikey.service.ApiKeyService;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.entity.RefreshTokenEntity;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.auth.service.JwtService;
import com.notify.common.model.*;
import com.notify.delivery.service.DeliveryOrchestrator;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantRevocationCascadeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private DeliveryOrchestrator deliveryOrchestrator;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String getAdminToken() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult result = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("accessToken").asText();
    }

    private record TenantFixture(UUID tenantId, UUID userId, String token) {}

    private TenantFixture createTenant(String name) throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String email = name.toLowerCase() + "-" + uniqueSuffix + "@notify.local";
        SignupRequest signup = new SignupRequest(name, email, "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        var claims = jwtService.parseToken(token);
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        return new TenantFixture(tenantId, userId, token);
    }

    private NotificationEntity createNotification(UUID tenantId, NotificationStatus status) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(status);
        n.setRecipient("user@example.com");
        n.setBody("Test notification body");
        n.setSubject("Test Subject");
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        if (status == NotificationStatus.DELIVERED || status == NotificationStatus.FAILED) {
            n.setCompletedAt(Instant.now());
        }
        n = notificationRepository.save(n);

        // Record initial status history
        NotificationStatusHistoryEntity h = new NotificationStatusHistoryEntity();
        h.setTenantId(tenantId);
        h.setNotificationId(n.getId());
        h.setFromStatus(null);
        h.setToStatus(status);
        h.setReason("Initial creation");
        h.setActor(Actor.TENANT);
        h.setOccurredAt(Instant.now());
        statusHistoryRepository.save(h);

        return n;
    }

    @Test
    @DisplayName("& Revocation cascade cancels notifications and revokes API keys & sessions")
    void testRevocationCascade() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("RevokeCascadeCorp");

        // 1. Create notifications in various states: SCHEDULED, QUEUED, RETRYING, and DELIVERED
        NotificationEntity scheduled = createNotification(tenant.tenantId(), NotificationStatus.SCHEDULED);
        NotificationEntity queued = createNotification(tenant.tenantId(), NotificationStatus.QUEUED);
        NotificationEntity retrying = createNotification(tenant.tenantId(), NotificationStatus.RETRYING);
        NotificationEntity delivered = createNotification(tenant.tenantId(), NotificationStatus.DELIVERED);

        // 2. Create active API keys
        apiKeyService.createKey(tenant.tenantId(),
                new CreateApiKeyRequest("Key 1", List.of("notifications:send"), null));
        apiKeyService.createKey(tenant.tenantId(),
                new CreateApiKeyRequest("Key 2", List.of("notifications:read"), null));

        List<ApiKeyEntity> activeKeysBefore = apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(activeKeysBefore).hasSize(2);
        assertThat(activeKeysBefore).allMatch(k -> k.getStatus() == ApiKeyStatus.ACTIVE);

        // 3. Verify active refresh tokens exist for tenant user
        List<RefreshTokenEntity> tokensBefore = refreshTokenRepository.findActiveByPrincipal(
                PrincipalType.TENANT_USER, tenant.userId(), Instant.now());
        assertThat(tokensBefore).isNotEmpty();

        // 4. Admin revokes the tenant
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(
                                TenantStatus.REVOKED, "Terms violation - full cascade test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 5. Verify notifications: SCHEDULED, QUEUED, RETRYING -> CANCELLED with cancel_reason='TENANT_REVOKED'
        NotificationEntity scheduledUpdated = notificationRepository.findById(scheduled.getId()).orElseThrow();
        assertThat(scheduledUpdated.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(scheduledUpdated.getCancelReason()).isEqualTo("TENANT_REVOKED");
        assertThat(scheduledUpdated.getCompletedAt()).isNotNull();

        NotificationEntity queuedUpdated = notificationRepository.findById(queued.getId()).orElseThrow();
        assertThat(queuedUpdated.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(queuedUpdated.getCancelReason()).isEqualTo("TENANT_REVOKED");
        assertThat(queuedUpdated.getCompletedAt()).isNotNull();

        NotificationEntity retryingUpdated = notificationRepository.findById(retrying.getId()).orElseThrow();
        assertThat(retryingUpdated.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(retryingUpdated.getCancelReason()).isEqualTo("TENANT_REVOKED");
        assertThat(retryingUpdated.getCompletedAt()).isNotNull();

        // Terminal notification is UNTOUCHED
        NotificationEntity deliveredUpdated = notificationRepository.findById(delivered.getId()).orElseThrow();
        assertThat(deliveredUpdated.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(deliveredUpdated.getCancelReason()).isNull();

        // 6. Verify notification_status_history rows
        List<NotificationStatusHistoryEntity> scheduledHistory =
                statusHistoryRepository.findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenant.tenantId(), scheduled.getId());
        NotificationStatusHistoryEntity lastScheduled = scheduledHistory.get(scheduledHistory.size() - 1);
        assertThat(lastScheduled.getToStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(lastScheduled.getReason()).isEqualTo("tenant-revoked side-effect");
        assertThat(lastScheduled.getActor()).isEqualTo(Actor.SYSTEM);

        // 7. Verify all API keys are REVOKED
        List<ApiKeyEntity> keysAfter = apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(keysAfter).allMatch(k -> k.getStatus() == ApiKeyStatus.REVOKED);

        // 8. Verify refresh tokens are revoked
        List<RefreshTokenEntity> tokensAfter = refreshTokenRepository.findActiveByPrincipal(
                PrincipalType.TENANT_USER, tenant.userId(), Instant.now());
        assertThat(tokensAfter).isEmpty();

        // 9. Worker protection (): Attempting delivery for revoked tenant returns TENANT_NOT_ACTIVE
        com.notify.notification.dto.ClaimSnapshot claim = new com.notify.notification.dto.ClaimSnapshot(
                queued.getId(), tenant.tenantId(), 1, 1, 1, 1, 3, 0,
                Channel.EMAIL, "recipient@example.com", "Subject", "Body",
                null, null, null, Instant.now(), Instant.now()
        );
        var outcome = deliveryOrchestrator.deliver(claim);
        assertThat(outcome).isInstanceOf(com.notify.delivery.model.PersistableOutcome.Fail.class);
        var fail = (com.notify.delivery.model.PersistableOutcome.Fail) outcome;
        assertThat(fail.errorCode()).isEqualTo(DeliveryOrchestrator.TENANT_NOT_ACTIVE);
        assertThat(fail.dlqReason()).isEqualTo(DlqReason.TENANT_NOT_ACTIVE);
    }

    @Test
    @DisplayName("Reactivation Credential Invariant () - reactivated tenant does NOT resurrect credentials")
    void testReactivationCredentialInvariant() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("ReactivateInvariantCorp");

        // Create API key
        apiKeyService.createKey(tenant.tenantId(),
                new CreateApiKeyRequest("Key To Be Revoked", List.of("notifications:send"), null));

        // Revoke the tenant
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(
                                TenantStatus.REVOKED, "Initial revoke"))))
                .andExpect(status().isOk());

        // Verify keys and sessions revoked
        List<ApiKeyEntity> revokedKeys = apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(revokedKeys).allMatch(k -> k.getStatus() == ApiKeyStatus.REVOKED);

        // Now reactivate the tenant (REVOKED -> ACTIVE)
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(
                                TenantStatus.ACTIVE, "Tenant reactivated upon review"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Verify DB tenant status is ACTIVE
        TenantEntity entity = tenantRepository.findById(tenant.tenantId()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(TenantStatus.ACTIVE);

        // Invariant: Previously revoked API keys remain REVOKED in DB!
        List<ApiKeyEntity> keysAfterReactivation = apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(keysAfterReactivation).allMatch(k -> k.getStatus() == ApiKeyStatus.REVOKED);

        // Invariant: Previously revoked refresh tokens remain revoked!
        List<RefreshTokenEntity> activeSessions = refreshTokenRepository.findActiveByPrincipal(
                PrincipalType.TENANT_USER, tenant.userId(), Instant.now());
        assertThat(activeSessions).isEmpty();
    }
}
