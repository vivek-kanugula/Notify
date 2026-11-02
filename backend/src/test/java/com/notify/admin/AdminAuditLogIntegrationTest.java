package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAuditLogIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

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

    private String getTenantToken() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "AuditTenant" + uniqueSuffix;
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private AdminAuditLogEntity createAuditEntry(UUID tenantId, String action, String targetType, Instant timestamp) {
        AdminAuditLogEntity entity = new AdminAuditLogEntity();
        entity.setAdminId(UUID.randomUUID());
        entity.setAdminEmail("admin@notify.local");
        entity.setAction(action);
        entity.setTargetType(targetType);
        entity.setTargetId(tenantId.toString());
        entity.setTenantId(tenantId);
        entity.setTenantSlug("tenant-" + tenantId.toString().substring(0, 8));
        entity.setReason("Audit action: " + action);
        entity.setIpAddress("127.0.0.1");
        entity.setCreatedAt(timestamp);
        return adminAuditLogRepository.save(entity);
    }

    @Test
    @DisplayName("Admin can query audit log with pagination and filters (tenantId, action, targetType, date range)")
    void testAuditLogPaginationAndFiltering() throws Exception {
        String adminToken = getAdminToken();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        Instant now = Instant.now();
        Instant pastHour = now.minus(1, ChronoUnit.HOURS);
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

        createAuditEntry(tenantA, "TENANT_SUSPENDED", "TENANT", twoHoursAgo);
        createAuditEntry(tenantA, "TENANT_REVOKED", "TENANT", pastHour);
        createAuditEntry(tenantB, "TENANT_REACTIVATED", "TENANT", now);

        // 1. Query by tenantId=tenantA
        MvcResult resTenant = mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("tenantId", tenantA.toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode jsonA = objectMapper.readTree(resTenant.getResponse().getContentAsString());
        assertThat(jsonA.get("content").size()).isGreaterThanOrEqualTo(2);
        for (JsonNode entry : jsonA.get("content")) {
            assertThat(entry.get("tenantId").asText()).isEqualTo(tenantA.toString());
        }

        // 2. Query by action=TENANT_REVOKED
        MvcResult resAction = mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("action", "TENANT_REVOKED"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonAction = objectMapper.readTree(resAction.getResponse().getContentAsString());
        for (JsonNode entry : jsonAction.get("content")) {
            assertThat(entry.get("action").asText()).isEqualTo("TENANT_REVOKED");
        }

        // 3. Query by date range: from pastHour minus 5min to now
        Instant from = pastHour.minus(5, ChronoUnit.MINUTES);
        Instant to = now.plus(5, ChronoUnit.MINUTES);

        MvcResult resRange = mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonRange = objectMapper.readTree(resRange.getResponse().getContentAsString());
        assertThat(jsonRange.get("content").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Invalid date range (from after to) or malformed date returns 422 VALIDATION_FAILED")
    void testAuditLogDateValidation() throws Exception {
        String adminToken = getAdminToken();

        Instant now = Instant.now();
        Instant past = now.minus(1, ChronoUnit.HOURS);

        // from is after to -> 422
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", now.toString())
                        .param("to", past.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // Malformed ISO date -> 422
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", "not-a-valid-date"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("& Audit log rejects anonymous (401) and tenant token (403)")
    void testAuditLogSecurityBoundaries() throws Exception {
        String tenantToken = getTenantToken();

        // Anonymous -> 401
        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized());

        // Tenant JWT -> 403
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tenantToken))
                .andExpect(status().isForbidden());
    }
}
