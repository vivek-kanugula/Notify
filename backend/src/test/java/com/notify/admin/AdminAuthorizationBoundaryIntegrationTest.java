package com.notify.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.admin.dto.UpdateTenantStatusRequest;
import com.notify.apikey.dto.ApiKeyCreatedResponse;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.service.ApiKeyService;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAuthorizationBoundaryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyService apiKeyService;

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

    private record TenantFixture(UUID tenantId, UUID userId, String token, String apiKey, String slug) {}

    private TenantFixture createTenant(String name) throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String email = name.toLowerCase() + "-" + uniqueSuffix + "@notify.local";
        SignupRequest signup = new SignupRequest(name, email, "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        var claims = jwtService.parseToken(token);
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        TenantEntity entity = tenantRepository.findById(tenantId).orElseThrow();

        ApiKeyCreatedResponse keyResponse = apiKeyService.createKey(tenantId,
                new CreateApiKeyRequest("Boundary Key", List.of("notifications:send"), null));

        return new TenantFixture(tenantId, userId, token, keyResponse.apiKey(), entity.getSlug());
    }

    @Test
    @DisplayName("Anonymous requests to any /api/v1/admin/* endpoint return 401")
    void anonymousAccessRejected() throws Exception {
        UUID randomId = UUID.randomUUID();

        // 1. GET /tenants
        mockMvc.perform(get("/api/v1/admin/tenants"))
                .andExpect(status().isUnauthorized());

        // 2. GET /tenants/{id}
        mockMvc.perform(get("/api/v1/admin/tenants/" + randomId))
                .andExpect(status().isUnauthorized());

        // 3. PATCH /tenants/{id}/status
        mockMvc.perform(patch("/api/v1/admin/tenants/" + randomId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "test"))))
                .andExpect(status().isUnauthorized());

        // 4. DELETE /tenants/{id}
        mockMvc.perform(delete("/api/v1/admin/tenants/" + randomId)
                        .header("X-Confirm-Tenant-Slug", "any-slug"))
                .andExpect(status().isUnauthorized());

        // 5. GET /audit-log
        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tenant user JWT to any /api/v1/admin/* endpoint returns 403 Forbidden")
    void tenantJwtAccessRejected() throws Exception {
        TenantFixture tenant = createTenant("TenantJwtBoundary");

        // 1. GET /tenants
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());

        // 2. GET /tenants/{id}
        mockMvc.perform(get("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());

        // 3. PATCH /tenants/{id}/status
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "test"))))
                .andExpect(status().isForbidden());

        // 4. DELETE /tenants/{id}
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.token())
                        .header("X-Confirm-Tenant-Slug", tenant.slug()))
                .andExpect(status().isForbidden());

        // 5. GET /audit-log
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API Key Bearer authentication to any /api/v1/admin/* endpoint returns 403 Forbidden")
    void apiKeyAccessRejected() throws Exception {
        TenantFixture tenant = createTenant("ApiKeyBoundary");

        // 1. GET /tenants
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + tenant.apiKey()))
                .andExpect(status().isForbidden());

        // 2. GET /tenants/{id}
        mockMvc.perform(get("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.apiKey()))
                .andExpect(status().isForbidden());

        // 3. PATCH /tenants/{id}/status
        mockMvc.perform(patch("/api/v1/admin/tenants/" + tenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "test"))))
                .andExpect(status().isForbidden());

        // 4. DELETE /tenants/{id}
        mockMvc.perform(delete("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.apiKey())
                        .header("X-Confirm-Tenant-Slug", tenant.slug()))
                .andExpect(status().isForbidden());

        // 5. GET /audit-log
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tenant.apiKey()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cross-audience JWT (tenant audience with admin claims) returns 403 Forbidden")
    void crossAudienceJwtRejected() throws Exception {
        // Generate a tenant token that carries user claims, not notify-admin audience
        TenantFixture tenant = createTenant("CrossAudienceBoundary");

        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }
}
