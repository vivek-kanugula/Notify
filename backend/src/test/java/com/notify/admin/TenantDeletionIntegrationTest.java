package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.apikey.service.ApiKeyService;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.entity.RefreshTokenEntity;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.common.model.PrincipalType;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.template.dto.CreateTemplateRequest;
import com.notify.template.service.TemplateService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.repository.TenantUserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantDeletionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantUserRepository tenantUserRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

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

    private record TenantFixture(UUID tenantId, UUID userId, String token, String slug) {}

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
        TenantEntity entity = tenantRepository.findById(tenantId).orElseThrow();
        return new TenantFixture(tenantId, userId, token, entity.getSlug());
    }

    @Test
    @DisplayName("Deletion slug validation - missing header returns 400, mismatched slug returns 422")
    void testDeletionSlugValidation() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("SlugValidationCorp");

        // 1. Missing header -> 400 Bad Request (MISSING_REQUEST_HEADER)
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUEST_HEADER"));

        // 2. Mismatched slug -> 422 Unprocessable Entity (VALIDATION_FAILED)
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Confirm-Tenant-Slug", "wrong-slug"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 3. Non-existent tenant -> 404 Not Found
        mockMvc.perform(delete("/api/v1/admin/tenants/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Confirm-Tenant-Slug", "any-slug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        // 4. Non-admin token -> 403 Forbidden
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("X-Confirm-Tenant-Slug", tenant.slug()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("& Tenant deletion cleans polymorphic sessions, cascades all rows, and persists audit log")
    void testTenantDeletionCompleteCascadeAndAudit() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("DeleteCascadeCorp");
        UUID tenantId = tenant.tenantId();
        UUID userId = tenant.userId();

        // 1. Create an active API key
        apiKeyService.createKey(tenantId,
                new CreateApiKeyRequest("Key to delete", List.of("notifications:send"), null));

        // 2. Create a template
        templateService.createTemplate(tenantId,
                new CreateTemplateRequest("tpl-delete", Channel.EMAIL, "Desc", "Subject", "Body", null));

        // 3. Create a notification
        NotificationEntity notification = new NotificationEntity();
        notification.setTenantId(tenantId);
        notification.setChannel(Channel.EMAIL);
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRecipient("recipient@example.com");
        notification.setSubject("Subject");
        notification.setBody("Body");
        notification.setCreatedAt(Instant.now());
        notification.setUpdatedAt(Instant.now());
        notificationRepository.save(notification);

        // Verify rows exist before deletion
        assertThat(tenantRepository.findById(tenantId)).isPresent();
        assertThat(tenantUserRepository.countByTenantId(tenantId)).isGreaterThan(0);
        assertThat(apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
        assertThat(notificationRepository.countByTenantId(tenantId)).isGreaterThan(0);
        List<RefreshTokenEntity> userTokensBefore = refreshTokenRepository.findActiveByPrincipal(
                PrincipalType.TENANT_USER, userId, Instant.now());
        assertThat(userTokensBefore).isNotEmpty();

        // 4. Execute deletion with exact slug header
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Confirm-Tenant-Slug", tenant.slug())
                        .param("reason", "Account deleted by request"))
                .andExpect(status().isNoContent());

        // 5. Post-condition verification - zero rows remain for tenantId across tables
        assertThat(tenantRepository.findById(tenantId)).isEmpty();
        assertThat(tenantUserRepository.countByTenantId(tenantId)).isEqualTo(0);
        assertThat(apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
        assertThat(notificationRepository.countByTenantId(tenantId)).isEqualTo(0);

        // 6. Invariant: zero polymorphic refresh_tokens remain for captured tenant user ID
        long remainingTokens = refreshTokenRepository.countByPrincipalTypeAndPrincipalIds(
                PrincipalType.TENANT_USER, List.of(userId));
        assertThat(remainingTokens).isEqualTo(0);

        // 7. Audit log persists in admin_audit_log with action=TENANT_DELETED
        List<AdminAuditLogEntity> auditLogs = adminAuditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        assertThat(auditLogs).hasSize(1);
        AdminAuditLogEntity audit = auditLogs.get(0);
        assertThat(audit.getAction()).isEqualTo("TENANT_DELETED");
        assertThat(audit.getTenantSlug()).isEqualTo(tenant.slug());
        assertThat(audit.getAdminEmail()).isEqualTo("admin@notify.local");
        assertThat(audit.getReason()).isEqualTo("Account deleted by request");
        assertThat(audit.getTargetType()).isEqualTo("TENANT");
        assertThat(audit.getTargetId()).isEqualTo(tenantId.toString());
    }
}
