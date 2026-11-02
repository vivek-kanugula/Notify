package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.admin.dto.UpdateTenantStatusRequest;
import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.TenantStatus;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTenantStatusTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
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

    private record TenantFixture(UUID tenantId, String token) {}

    private TenantFixture createTenant(String name) throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + uniqueSuffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        TenantEntity entity = tenantRepository.findAll().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst().orElseThrow();
        return new TenantFixture(entity.getId(), token);
    }

    @Test
    @DisplayName("Admin suspends tenant with audit log record")
    void adminSuspendTenantWithAuditLog() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("SuspendedCorp");

        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(
                TenantStatus.SUSPENDED,
                "Billing overdue - investigation required"
        );

        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.statusReason").value("Billing overdue - investigation required"))
                .andExpect(jsonPath("$.statusChangedAt").isNotEmpty());

        // Verify DB entity
        TenantEntity updated = tenantRepository.findById(tenant.tenantId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TenantStatus.SUSPENDED);

        // Verify audit log
        List<AdminAuditLogEntity> logs = adminAuditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(logs).isNotEmpty();
        AdminAuditLogEntity log = logs.get(0);
        assertThat(log.getAction()).isEqualTo("TENANT_SUSPENDED");
        assertThat(log.getAdminEmail()).isEqualTo("admin@notify.local");
        assertThat(log.getTargetType()).isEqualTo("TENANT");
        assertThat(log.getTargetId()).isEqualTo(tenant.tenantId().toString());
        assertThat(log.getReason()).isEqualTo("Billing overdue - investigation required");
    }

    @Test
    @DisplayName("Admin revokes tenant with audit log record")
    void adminRevokeTenantWithAuditLog() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("RevokedCorp");

        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(
                TenantStatus.REVOKED,
                "Confirmed fraudulent activity"
        );

        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        TenantEntity updated = tenantRepository.findById(tenant.tenantId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TenantStatus.REVOKED);

        List<AdminAuditLogEntity> logs = adminAuditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(logs.get(0).getAction()).isEqualTo("TENANT_REVOKED");
    }

    @Test
    @DisplayName("Admin reactivates suspended tenant")
    void adminReactivateTenant() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("ReactivateCorp");

        // Suspend
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "Pending review"))))
                .andExpect(status().isOk());

        // Reactivate
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.ACTIVE, "Review completed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        TenantEntity updated = tenantRepository.findById(tenant.tenantId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TenantStatus.ACTIVE);

        List<AdminAuditLogEntity> logs = adminAuditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(logs.get(0).getAction()).isEqualTo("TENANT_REACTIVATED");
    }

    @Test
    @DisplayName("REVOKED tenant can be reactivated to ACTIVE")
    void revokedTenantCanBeReactivated() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("TerminalCorp");

        // Revoke
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.REVOKED, "Final termination"))))
                .andExpect(status().isOk());

        // Reactivate from REVOKED -> ACTIVE (permits this)
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.ACTIVE, "Reactivation after review"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        TenantEntity updated = tenantRepository.findById(tenant.tenantId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TenantStatus.ACTIVE);

        List<AdminAuditLogEntity> logs = adminAuditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.tenantId());
        assertThat(logs.get(0).getAction()).isEqualTo("TENANT_REACTIVATED");
    }

    @Test
    @DisplayName("REVOKED -> SUSPENDED is rejected with 409 INVALID_STATE_TRANSITION")
    void revokedToSuspendedIsInvalid() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("RevokedSuspendCorp");

        // Revoke
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.REVOKED, "Terminated"))))
                .andExpect(status().isOk());

        // Try REVOKED -> SUSPENDED (rejects this)
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "Try suspend revoked"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("Cross-audience security: Tenant JWT cannot access admin tenant status endpoint (403 FORBIDDEN)")
    void tenantCannotAccessAdminEndpoint() throws Exception {
        TenantFixture tenant = createTenant("UnauthorizedTenant");

        UpdateTenantStatusRequest req = new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "Self-sabotage");
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Status update requires non-blank reason (422 VALIDATION_FAILED)")
    void reasonRequiredForStatusChange() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("NoReasonCorp");

        String invalidBody = "{\"status\":\"SUSPENDED\",\"reason\":\"   \"}";
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
