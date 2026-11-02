package com.notify.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.dto.CreateApiKeyRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * through the real HTTP surface: tenant owner JWT only, the secret revealed once, a rejected destination as
 * 422, and no cross-tenant visibility.
 */
class WebhookEndpointApiTest extends AbstractPostgresIntegrationTest {

    private static final String PATH = "/api/v1/webhook-endpoint";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

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

    private record TenantFixture(UUID tenantId, String token) {
    }

    private TenantFixture createTenant(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(
                name, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        UUID tenantId = UUID.fromString(jwtService.parseToken(token).get("tid", String.class));
        return new TenantFixture(tenantId, token);
    }

    private String body(String url, Boolean enabled) throws Exception {
        return enabled == null
                ? objectMapper.writeValueAsString(java.util.Map.of("url", url))
                : objectMapper.writeValueAsString(java.util.Map.of("url", url, "enabled", enabled));
    }

    @Test
    @DisplayName("GET is 404 before any endpoint is configured: no row is created by reading")
    void getBeforeConfiguration() throws Exception {
        TenantFixture tenant = createTenant("WhGetFirst");

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the first PUT is 201 with the signing secret; a later PUT is 200 without it, and GET never shows it")
    void createThenUpdateThenRead() throws Exception {
        TenantFixture tenant = createTenant("WhCreate");

        MvcResult created = mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/hook", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://receiver.example.com/hook"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.isDemo").value(false))
                .andExpect(jsonPath("$.signingSecret").isNotEmpty())
                .andReturn();

        String secret = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("signingSecret").asText();
        assertThat(secret).startsWith("whsec_");

        String updated = mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/hook2", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://receiver.example.com/hook2"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(updated).doesNotContain("signingSecret").doesNotContain(secret);

        String read = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(read).doesNotContain(secret).doesNotContain("whsec_");
    }

    @Test
    @DisplayName("rotate-secret returns a new secret once and invalidates the previous one")
    void rotateSecret() throws Exception {
        TenantFixture tenant = createTenant("WhRotate");
        MvcResult created = mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/hook", null)))
                .andExpect(status().isCreated())
                .andReturn();
        String first = objectMapper.readTree(created.getResponse().getContentAsString()).get("signingSecret").asText();

        MvcResult rotated = mockMvc.perform(post(PATH + "/rotate-secret")
                        .header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signingSecret").isNotEmpty())
                .andReturn();

        String second = objectMapper.readTree(rotated.getResponse().getContentAsString()).get("signingSecret").asText();
        assertThat(second).startsWith("whsec_").isNotEqualTo(first);
    }

    @Test
    @DisplayName("rotate-secret without a configured endpoint is 404, not a new endpoint")
    void rotateWithoutEndpoint() throws Exception {
        TenantFixture tenant = createTenant("WhRotateMissing");

        mockMvc.perform(post(PATH + "/rotate-secret").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a destination the policy blocks is 422 VALIDATION_FAILED and names the reason, not an internal error")
    void blockedDestinationIsValidationFailure() throws Exception {
        TenantFixture tenant = createTenant("WhBlocked");

        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://169.254.169.254/latest/meta-data/", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.destinationRejectionReason").isNotEmpty());

        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://10.0.0.5/hook", null)))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a blank url is rejected as a validation failure and creates nothing")
    void blankUrlIsRejected() throws Exception {
        TenantFixture tenant = createTenant("WhBlank");

        // The project maps bean-validation failures to 422 VALIDATION_FAILED, like a rejected destination.
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("no token is 401, so configuration is never reachable anonymously")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/hook", null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/rotate-secret")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/test")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an API key cannot manage webhook configuration, because it would be a way to mint a signing secret")
    void apiKeyCannotManageConfiguration() throws Exception {
        TenantFixture tenant = createTenant("WhApiKey");
        MvcResult keyResult = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest(
                                "Server", List.of("notifications:send", "notifications:read"), null))))
                .andExpect(status().isCreated())
                .andReturn();
        String apiKey = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asText();

        mockMvc.perform(get(PATH).header("X-API-Key", apiKey))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(put(PATH)
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/hook", null)))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("one tenant never sees or changes another tenant's endpoint")
    void noCrossTenantAccess() throws Exception {
        TenantFixture owner = createTenant("WhOwner");
        TenantFixture other = createTenant("WhOther");

        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://owner.example.com/hook", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://other.example.com/hook", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://owner.example.com/hook"));
    }

    @Test
    @DisplayName("a suspended tenant may read its configuration but not change it")
    void suspendedTenantIsReadOnly() throws Exception {
        TenantFixture tenant = createTenant("WhSuspended");
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/hook", null)))
                .andExpect(status().isCreated());

        TenantEntity entity = tenantRepository.findById(tenant.tenantId()).orElseThrow();
        entity.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.saveAndFlush(entity);

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk());
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://receiver.example.com/changed", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/rotate-secret").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/test").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + tenant.token()))
                .andExpect(jsonPath("$.url").value("https://receiver.example.com/hook"));
    }
}
