package com.notify.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Channel;
import com.notify.common.model.ContentType;
import com.notify.common.model.TemplateVersionStatus;
import com.notify.template.dto.CreateTemplateRequest;
import com.notify.template.dto.CreateVersionRequest;
import com.notify.template.dto.PreviewTemplateRequest;
import com.notify.template.dto.UpdateVersionRequest;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TemplateIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

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

    private record TenantFixture(UUID tenantId, UUID userId, String token) {}

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
        var claims = jwtService.parseToken(token);
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        return new TenantFixture(tenantId, userId, token);
    }

    private String createApiKey(TenantFixture fixture, List<String> scopes) throws Exception {
        CreateApiKeyRequest req = new CreateApiKeyRequest("Key-" + UUID.randomUUID().toString().substring(0, 5), scopes, null);
        MvcResult res = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.get("apiKey").asText();
    }

    @Test
    @DisplayName("Create template with draft version 1, and reject EMAIL without subject")
    void createTemplateValidation() throws Exception {
        TenantFixture tenant = createTenant("TemplateTenant1");

        // 1. Missing subject on EMAIL channel fails with 422 VALIDATION_FAILED
        CreateTemplateRequest invalidReq = new CreateTemplateRequest(
                "Welcome", Channel.EMAIL, "Desc", "", "Hello {{name}}", ContentType.TEXT
        );
        mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 2. Valid template creates template and initial draft version 1
        CreateTemplateRequest validReq = new CreateTemplateRequest(
                "Welcome Email", Channel.EMAIL, "Welcome email description",
                "Welcome to {{appName}}, {{name}}!",
                "Hello {{name}}, welcome to {{appName}}.",
                ContentType.TEXT
        );
        MvcResult result = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Welcome Email"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.currentVersion.versionNumber").value(1))
                .andExpect(jsonPath("$.currentVersion.status").value("DRAFT"))
                .andExpect(jsonPath("$.currentVersion.variables[0]").value("appName"))
                .andExpect(jsonPath("$.currentVersion.variables[1]").value("name"))
                .andReturn();

        // 3. Duplicate name in same tenant returns 422 VALIDATION_FAILED
        mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Template soft deletion hides from lists and detail, but versions remain readable")
    void softDeleteAndVersionReadability() throws Exception {
        TenantFixture tenant = createTenant("SoftDelTenant");

        CreateTemplateRequest req = new CreateTemplateRequest(
                "Order Status", Channel.WEBHOOK, "Order updates", null,
                "{\"orderId\": \"{{orderId}}\"}", ContentType.TEXT
        );
        MvcResult createdRes = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(createdRes.getResponse().getContentAsString());
        String templateId = root.get("id").asText();
        String v1Id = root.get("currentVersion").get("id").asText();

        // 1. Appears in template list
        mockMvc.perform(get("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + templateId + "')]").exists());

        // 2. Soft delete template
        mockMvc.perform(delete("/api/v1/templates/" + templateId)
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNoContent());

        // 3. Hidden from list
        mockMvc.perform(get("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + templateId + "')]").doesNotExist());

        // 4. Detail endpoint returns 404
        mockMvc.perform(get("/api/v1/templates/" + templateId)
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());

        // 5. Version remains readable via /templates/{id}/versions/1 (by version number)
        mockMvc.perform(get("/api/v1/templates/" + templateId + "/versions/1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(v1Id))
                .andExpect(jsonPath("$.versionNumber").value(1));

        // 6. Versions list remains readable via /templates/{id}/versions
        mockMvc.perform(get("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(v1Id))
                .andExpect(jsonPath("$[0].versionNumber").value(1));

        // 7. Version remains readable via direct version endpoint /templates/versions/{v1Id}
        mockMvc.perform(get("/api/v1/templates/versions/" + v1Id)
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(v1Id));
    }

    @Test
    @DisplayName("Cross-tenant access to templates and versions returns 404 NOT_FOUND")
    void crossTenantIsolation() throws Exception {
        TenantFixture tenantA = createTenant("TenantA");
        TenantFixture tenantB = createTenant("TenantB");

        CreateTemplateRequest req = new CreateTemplateRequest(
                "Secret Template", Channel.IN_APP, "Internal", null,
                "Message: {{msg}}", ContentType.TEXT
        );
        MvcResult res = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenantA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        String templateId = root.get("id").asText();
        String versionId = root.get("currentVersion").get("id").asText();

        // Tenant B cannot get Tenant A's template
        mockMvc.perform(get("/api/v1/templates/" + templateId)
                        .header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound());

        // Tenant B cannot list Tenant A's versions
        mockMvc.perform(get("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound());

        // Tenant B cannot get Tenant A's version by version number
        mockMvc.perform(get("/api/v1/templates/" + templateId + "/versions/1")
                        .header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound());

        // Tenant B cannot get Tenant A's version via direct lookup
        mockMvc.perform(get("/api/v1/templates/versions/" + versionId)
                        .header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Draft version editing succeeds, but published version is immutable (409)")
    void versionLifecycleAndImmutability() throws Exception {
        TenantFixture tenant = createTenant("ImmutableTenant");

        CreateTemplateRequest req = new CreateTemplateRequest(
                "Notification Template", Channel.EMAIL, "Test", "Subject {{code}}",
                "Body {{code}}", ContentType.TEXT
        );
        MvcResult res = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        String templateId = root.get("id").asText();

        // 1. Edit draft v1 succeeds via PUT /templates/{id}/versions/1
        UpdateVersionRequest updateReq = new UpdateVersionRequest("New Subject {{code}}", "New Body {{code}}", ContentType.TEXT);
        mockMvc.perform(put("/api/v1/templates/" + templateId + "/versions/1")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("New Subject {{code}}"));

        // 2. Publish v1 via POST /templates/{id}/versions/1/publish
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/1/publish")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 3. Attempting to edit published v1 fails with 409 TEMPLATE_VERSION_IMMUTABLE
        mockMvc.perform(put("/api/v1/templates/" + templateId + "/versions/1")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TEMPLATE_VERSION_IMMUTABLE"));

        // 4. Create draft v2 via POST /templates/{id}/versions
        CreateVersionRequest v2Req = new CreateVersionRequest("Subject v2", "Body v2", ContentType.TEXT);
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v2Req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 5. Publish v2 -> v1 is transitioned to ARCHIVED and v2 is PUBLISHED
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/2/publish")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/templates/" + templateId + "/versions/1")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 6. List versions via GET /templates/{id}/versions returns both v2 and v1
        mockMvc.perform(get("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[1].versionNumber").value(1));
    }

    @Test
    @DisplayName("Preview renders variables, escapes HTML, and rejects missing variables (422)")
    void previewRenderingAndMissingVariables() throws Exception {
        TenantFixture tenant = createTenant("PreviewTenant");

        CreateTemplateRequest htmlReq = new CreateTemplateRequest(
                "HTML Welcome", Channel.EMAIL, "Html email",
                "Hello {{user.name}}",
                "<h1>Welcome</h1><p>Your token is {{token}} & organization is {{org}}.</p>",
                ContentType.HTML
        );
        MvcResult res = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(htmlReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        String templateId = root.get("id").asText();

        // 1. Missing variable (e.g. 'token') returns 422 TEMPLATE_VARIABLE_MISSING
        PreviewTemplateRequest missingVarReq = new PreviewTemplateRequest(Map.of(
                "user", Map.of("name", "Alice"),
                "org", "Acme"
        ));
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/1/preview")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingVarReq)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TEMPLATE_VARIABLE_MISSING"));

        // 2. All variables present: HTML escaping applied to variable values
        PreviewTemplateRequest completeReq = new PreviewTemplateRequest(Map.of(
                "user", Map.of("name", "Alice & Bob"),
                "token", "<script>bad()</script>",
                "org", "Acme & Co"
        ));
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions/1/preview")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Hello Alice & Bob"))
                .andExpect(jsonPath("$.body").value("<h1>Welcome</h1><p>Your token is &lt;script&gt;bad()&lt;/script&gt; & organization is Acme &amp; Co.</p>"));
    }

    @Test
    @DisplayName("Draft version cloning copies content from existing version and supports selective overrides")
    void draftVersionCloning() throws Exception {
        TenantFixture tenant = createTenant("CloneTenant");

        CreateTemplateRequest templateReq = new CreateTemplateRequest(
                "Order Invoice", Channel.EMAIL, "Invoice email",
                "Invoice for {{orderId}}",
                "Hello {{customer}}, here is invoice {{orderId}} for amount {{amount}}.",
                ContentType.TEXT
        );
        MvcResult res = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String templateId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();

        // 1. Clone version 1 into draft version 2 without overrides
        CreateVersionRequest cloneReq = new CreateVersionRequest(1, null, null, null);
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cloneReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.subject").value("Invoice for {{orderId}}"))
                .andExpect(jsonPath("$.body").value("Hello {{customer}}, here is invoice {{orderId}} for amount {{amount}}."))
                .andExpect(jsonPath("$.variables", containsInAnyOrder("amount", "customer", "orderId")));

        // 2. Clone version 1 into draft version 3 with subject override
        CreateVersionRequest overrideReq = new CreateVersionRequest(1, "Updated Subject {{orderId}}", null, null);
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(3))
                .andExpect(jsonPath("$.subject").value("Updated Subject {{orderId}}"))
                .andExpect(jsonPath("$.body").value("Hello {{customer}}, here is invoice {{orderId}} for amount {{amount}}."));

        // 2b. Clone version 1 into draft version 4 with body override
        CreateVersionRequest bodyOverrideReq = new CreateVersionRequest(1, null, "Overridden body for {{customer}} with discount {{discount}}.", null);
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyOverrideReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(4))
                .andExpect(jsonPath("$.subject").value("Invoice for {{orderId}}"))
                .andExpect(jsonPath("$.body").value("Overridden body for {{customer}} with discount {{discount}}."))
                .andExpect(jsonPath("$.variables", containsInAnyOrder("customer", "discount", "orderId")));

        // 2c. Clone version 1 into draft version 5 with both subject and body override
        CreateVersionRequest bothReq = new CreateVersionRequest(1, "Special Notice {{orderId}}", "Special body {{orderId}} for {{amount}}", ContentType.TEXT);
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bothReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(5))
                .andExpect(jsonPath("$.subject").value("Special Notice {{orderId}}"))
                .andExpect(jsonPath("$.body").value("Special body {{orderId}} for {{amount}}"))
                .andExpect(jsonPath("$.variables", containsInAnyOrder("amount", "orderId")));

        // 3. Attempting to clone non-existent version returns 404 NOT_FOUND
        CreateVersionRequest invalidCloneReq = new CreateVersionRequest(99, null, null, null);
        mockMvc.perform(post("/api/v1/templates/" + templateId + "/versions")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCloneReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("API Key scopes: templates:read vs templates:write vs wrong scope (403 INSUFFICIENT_SCOPE)")
    void apiKeyScopeEnforcement() throws Exception {
        TenantFixture tenant = createTenant("ScopeTenant");

        String readOnlyKey = createApiKey(tenant, List.of("templates:read"));
        String writeKey = createApiKey(tenant, List.of("templates:write"));
        String sendOnlyKey = createApiKey(tenant, List.of("notifications:send"));

        CreateTemplateRequest req = new CreateTemplateRequest(
                "Scoped Template", Channel.IN_APP, "Test", null,
                "Alert: {{msg}}", ContentType.TEXT
        );

        // 1. sendOnlyKey cannot read or write templates -> 403 INSUFFICIENT_SCOPE
        mockMvc.perform(get("/api/v1/templates")
                        .header("X-API-Key", sendOnlyKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_SCOPE"));

        mockMvc.perform(post("/api/v1/templates")
                        .header("X-API-Key", sendOnlyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_SCOPE"));

        // 2. readOnlyKey can read, but cannot create -> 403 INSUFFICIENT_SCOPE
        mockMvc.perform(get("/api/v1/templates")
                        .header("X-API-Key", readOnlyKey))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/templates")
                        .header("X-API-Key", readOnlyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_SCOPE"));

        // 3. writeKey can create
        mockMvc.perform(post("/api/v1/templates")
                        .header("X-API-Key", writeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Scoped Template"));
    }
}
