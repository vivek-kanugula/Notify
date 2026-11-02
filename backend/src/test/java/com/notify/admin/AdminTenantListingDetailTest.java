package com.notify.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.admin.dto.UpdateTenantStatusRequest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.service.ApiKeyService;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Channel;
import com.notify.common.model.TenantStatus;
import com.notify.template.dto.CreateTemplateRequest;
import com.notify.template.service.TemplateService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTenantListingDetailTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TemplateService templateService;

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

    private record TenantFixture(UUID tenantId, UUID userId, String token, String slug, String name) {}

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
        return new TenantFixture(tenantId, userId, token, entity.getSlug(), entity.getName());
    }

    @Test
    @DisplayName("Admin can list tenants with pagination and projections (userCount, activeApiKeyCount, templateCount)")
    void adminListTenantsWithProjections() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("AcmeCorpListTest");

        // Add 1 active API key
        apiKeyService.createKey(tenant.tenantId(),
                new CreateApiKeyRequest("Test Key", List.of("notifications:send"), null));

        // Add 1 template
        templateService.createTemplate(tenant.tenantId(),
                new CreateTemplateRequest("welcome-mail", Channel.EMAIL, "Welcome description", "Welcome!", "Hello {{name}}", null));

        // Call GET /api/v1/admin/tenants
        MvcResult result = mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode items = json.get("content");
        assertThat(items.isArray()).isTrue();

        JsonNode matched = null;
        for (JsonNode item : items) {
            if (item.get("id").asText().equals(tenant.tenantId().toString())) {
                matched = item;
                break;
            }
        }
        assertThat(matched).isNotNull();
        assertThat(matched.get("name").asText()).isEqualTo("AcmeCorpListTest");
        assertThat(matched.get("slug").asText()).isEqualTo(tenant.slug());
        assertThat(matched.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(matched.get("userCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(matched.get("activeApiKeyCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(matched.get("templateCount").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Admin can filter tenants by status and substring query")
    void adminFilterTenantsByStatusAndQuery() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture activeTenant = createTenant("FilterActiveCorp");
        TenantFixture suspendedTenant = createTenant("FilterSuspendedCorp");

        // Suspend the second tenant
        mockMvc.perform(patch("/api/v1/admin/tenants/" + suspendedTenant.tenantId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTenantStatusRequest(TenantStatus.SUSPENDED, "Testing filter"))))
                .andExpect(status().isOk());

        // Filter by status=SUSPENDED and query="filtersuspended"
        MvcResult res = mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "SUSPENDED")
                        .param("query", "filtersuspended"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        JsonNode items = json.get("content");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isGreaterThanOrEqualTo(1);

        for (JsonNode item : items) {
            assertThat(item.get("status").asText()).isEqualTo("SUSPENDED");
            assertThat(item.get("name").asText().toLowerCase()).contains("filtersuspended");
        }
    }

    @Test
    @DisplayName("Admin can get tenant details with complete aggregate counts")
    void adminGetTenantDetail() throws Exception {
        String adminToken = getAdminToken();
        TenantFixture tenant = createTenant("DetailTestCorp");

        // Add 2 active API keys
        apiKeyService.createKey(tenant.tenantId(),
                new CreateApiKeyRequest("Key 1", List.of("notifications:send"), null));
        apiKeyService.createKey(tenant.tenantId(),
                new CreateApiKeyRequest("Key 2", List.of("notifications:read"), null));

        // Add 1 template
        templateService.createTemplate(tenant.tenantId(),
                new CreateTemplateRequest("detail-tpl", Channel.EMAIL, "Detail Description", "Detail Subject", "Detail body", null));

        // Call GET /api/v1/admin/tenants/{id}
        mockMvc.perform(get("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenant.tenantId().toString()))
                .andExpect(jsonPath("$.name").value("DetailTestCorp"))
                .andExpect(jsonPath("$.slug").value(tenant.slug()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userCount").value(1))
                .andExpect(jsonPath("$.activeApiKeyCount").value(2))
                .andExpect(jsonPath("$.templateCount").value(1))
                .andExpect(jsonPath("$.hasWebhookConfigured").value(false));
    }

    @Test
    @DisplayName("Non-existent tenant detail returns 404 NOT_FOUND")
    void adminGetNonExistentTenantReturns404() throws Exception {
        String adminToken = getAdminToken();
        UUID nonExistent = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/admin/tenants/" + nonExistent)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("& Tenant JWT and anonymous access rejected with 401/403")
    void nonAdminCannotAccessTenantEndpoints() throws Exception {
        TenantFixture tenant = createTenant("ForbiddenCheckOrg");

        // Anonymous GET list -> 401
        mockMvc.perform(get("/api/v1/admin/tenants"))
                .andExpect(status().isUnauthorized());

        // Tenant JWT GET list -> 403
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());

        // Tenant JWT GET detail -> 403
        mockMvc.perform(get("/api/v1/admin/tenants/" + tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());
    }
}
