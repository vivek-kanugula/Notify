package com.notify.isolation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.model.ApiKeyStatus;
import com.notify.common.model.Channel;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.template.dto.CreateTemplateRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * / -1: the tenant-facing half of the endpoint isolation and scope matrix.
 *
 * <p>Two invariants, asserted per resource type rather than per feature suite:
 * <ol>
 *   <li><b>Cross-tenant reads are 404, never 403.</b> A 403 would confirm the id exists in another tenant;
 *       404 leaks nothing.</li>
 *   <li><b>API-key scopes are enforced per endpoint</b>, with a positive control so the denials cannot be a
 *       blanket rejection of key auth.</li>
 * </ol>
 *
 * <p>Scope names come from {@code ApiKeyScope}: {@code notifications:send}, {@code notifications:read},
 * {@code notifications:cancel}, {@code templates:read}, {@code templates:write}, {@code inapp:stream},
 * {@code usage:read}. There is no {@code notifications:write} scope, despite the plan's §3.3.1 wording.
 *
 * <p>{@code /api/v1/webhook-endpoint} is deliberately absent: it is a per-tenant singleton with no
 * id-addressed route, so the plan's "Tenant B's webhook endpoint ID -> 404" case cannot exist. Its isolation
 * is structural (the controller resolves the endpoint from {@code TenantContext}, never from a path id).
 */
class TenantEndpointIsolationMatrixTest extends AbstractPostgresIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ApiKeyRepository apiKeyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        this.tokenA = signup("IsolationTenantA");
        this.tokenB = signup("IsolationTenantB");
    }

    private String signup(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(
                name + "-" + suffix, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String createNotification(String token) throws Exception {
        CreateNotificationRequest request = new CreateNotificationRequest(
                Channel.EMAIL, "owner@example.com", "Subject", "Body",
                null, null, null, null, null, null);
        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createTemplate(String token) throws Exception {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "tpl-" + UUID.randomUUID().toString().substring(0, 8),
                Channel.EMAIL, "desc", "Subject", "Hello {{name}}", null);
        MvcResult result = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** @return the created key's id and its one-time plaintext. */
    private String[] createKey(String token, List<String> scopes) throws Exception {
        CreateApiKeyRequest request = new CreateApiKeyRequest("key-" + UUID.randomUUID(), scopes, null);
        MvcResult result = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new String[] { node.get("id").asText(), node.get("apiKey").asText() };
    }

    // ─── 1. cross-tenant reads return 404 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tenant A reading tenant B's notification, history and cancel route gets 404")
    void crossTenantNotificationIs404() throws Exception {
        String notificationB = createNotification(tokenB);

        mockMvc.perform(get("/api/v1/notifications/" + notificationB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/notifications/" + notificationB + "/history")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/notifications/" + notificationB + "/cancel")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        // Control: the owner still sees it, so 404 is isolation and not a broken fixture.
        mockMvc.perform(get("/api/v1/notifications/" + notificationB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("tenant A reading tenant B's template gets 404 while B still sees it")
    void crossTenantTemplateIs404() throws Exception {
        String templateB = createTemplate(tokenB);

        mockMvc.perform(get("/api/v1/templates/" + templateB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/templates/" + templateB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("tenant A deleting tenant B's API key gets 404 and B's key stays active")
    void crossTenantApiKeyIs404() throws Exception {
        String[] keyB = createKey(tokenB, List.of("notifications:send"));
        UUID keyBId = UUID.fromString(keyB[0]);

        // /api/v1/keys exposes no GET /{id}, so the id-addressed route is the delete.
        mockMvc.perform(delete("/api/v1/keys/" + keyBId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        ApiKeyEntity stillThere = apiKeyRepository.findById(keyBId).orElseThrow();
        assertThat(stillThere.getStatus())
                .as("a cross-tenant delete must not revoke the owner's key")
                .isEqualTo(ApiKeyStatus.ACTIVE);
    }

    // ─── 2. API-key scope enforcement ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a notifications:read key cannot send, and a notifications:send key cannot read templates")
    void scopesAreEnforcedPerEndpoint() throws Exception {
        String readOnlyKey = createKey(tokenA, List.of("notifications:read"))[1];
        String sendOnlyKey = createKey(tokenA, List.of("notifications:send"))[1];

        CreateNotificationRequest send = new CreateNotificationRequest(
                Channel.EMAIL, "scoped@example.com", "Subject", "Body",
                null, null, null, null, null, null);

        // read-only key may not send
        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-API-Key", readOnlyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(send)))
                .andExpect(status().isForbidden());

        // send-only key may not read templates
        mockMvc.perform(get("/api/v1/templates").header("X-API-Key", sendOnlyKey))
                .andExpect(status().isForbidden());

        // send-only key may not list notifications either (that needs notifications:read)
        mockMvc.perform(get("/api/v1/notifications").header("X-API-Key", sendOnlyKey))
                .andExpect(status().isForbidden());

        // Positive control: the read key can read, so the denials above are scope checks, not blanket
        // rejection of API-key authentication.
        mockMvc.perform(get("/api/v1/notifications").header("X-API-Key", readOnlyKey))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an API key never reaches another tenant's notification")
    void apiKeyCannotCrossTenants() throws Exception {
        String notificationB = createNotification(tokenB);
        String readKeyA = createKey(tokenA, List.of("notifications:read"))[1];

        mockMvc.perform(get("/api/v1/notifications/" + notificationB)
                        .header("X-API-Key", readKeyA))
                .andExpect(status().isNotFound());
    }
}
