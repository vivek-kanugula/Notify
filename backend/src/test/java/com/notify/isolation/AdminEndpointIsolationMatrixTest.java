package com.notify.isolation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
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
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * / -1: the admin half of the boundary matrix — the two directions of the admin/tenant split,
 * asserted across every admin route family at once rather than one feature suite at a time.
 *
 * <ol>
 *   <li>A tenant JWT ({@code aud=notify-tenant}) and an API key never reach {@code /api/v1/admin/**}.</li>
 *   <li>An admin JWT ({@code aud=notify-admin}) never reaches the tenant API, because it carries no tenant
 *       context and the default chain explicitly excludes {@code PLATFORM_ADMIN}.</li>
 *   <li>Anonymous callers get 401 on admin routes, not 403 — the entry point, not access-denied.</li>
 * </ol>
 *
 * <p>The verified audience literal is {@code notify-tenant} ({@code JwtService}, {@code JwtAuthenticationFilter},
 * {@code TenantAuthController}); the plan's §3.3.1 reference to {@code aud=notify-api} does not exist in
 * this codebase.
 */
class AdminEndpointIsolationMatrixTest extends AbstractPostgresIntegrationTest {

    /** Every admin route family a tenant principal must never reach. */
    private static Stream<String> adminRoutes() {
        return Stream.of(
                "/api/v1/admin/tenants",
                "/api/v1/admin/analytics/overview",
                "/api/v1/admin/notifications",
                "/api/v1/admin/audit-log",
                "/api/v1/admin/system/status",
                "/api/v1/admin/dlq");
    }

    @Autowired private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    private String tenantToken;
    private String tenantApiKey;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(
                "AdminBoundaryOrg-" + suffix, "admin-boundary-" + suffix + "@notify.local", "Password123!");
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();
        this.tenantToken = objectMapper.readTree(signupResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        CreateApiKeyRequest keyRequest = new CreateApiKeyRequest(
                "boundary-key", List.of("notifications:send", "notifications:read"), null);
        MvcResult keyResult = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(keyRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode keyNode = objectMapper.readTree(keyResult.getResponse().getContentAsString());
        this.tenantApiKey = keyNode.get("apiKey").asText();

        LoginRequest adminLogin = new LoginRequest(
                "admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult adminResult = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminLogin)))
                .andExpect(status().isOk())
                .andReturn();
        this.adminToken = objectMapper.readTree(adminResult.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    @DisplayName("a tenant JWT is forbidden on every admin route family")
    void tenantJwtCannotReachAdminRoutes() throws Exception {
        for (String route : adminRoutes().toList()) {
            mockMvc.perform(get(route).header("Authorization", "Bearer " + tenantToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a tenant API key is forbidden on every admin route family")
    void apiKeyCannotReachAdminRoutes() throws Exception {
        for (String route : adminRoutes().toList()) {
            mockMvc.perform(get(route).header("X-API-Key", tenantApiKey))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("an anonymous caller gets 401 on admin routes, not 403")
    void anonymousGets401OnAdminRoutes() throws Exception {
        for (String route : adminRoutes().toList()) {
            mockMvc.perform(get(route)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("an admin JWT cannot act on the tenant API")
    void adminJwtCannotReachTenantApi() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/templates").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/usage/summary").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the admin JWT does reach the admin API (positive control)")
    void adminJwtReachesAdminApi() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
