package com.notify.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.apikey.service.ApiKeyService;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.ApiKeyStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiKeyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

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

    @Test
    @DisplayName("Create API key returns one-time plaintext key and list endpoint does not leak plaintext")
    void createAndListApiKey() throws Exception {
        TenantFixture fixture = createTenant("AcmeCorp");

        CreateApiKeyRequest request = new CreateApiKeyRequest(
                "Production Server",
                List.of("notifications:send", "notifications:read"),
                null
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Production Server"))
                .andExpect(jsonPath("$.keyPrefix").isNotEmpty())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode createdNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String plaintextKey = createdNode.get("apiKey").asText();
        assertThat(plaintextKey).startsWith("ntf_");
        assertThat(plaintextKey).hasSize(44);

        // List keys
        mockMvc.perform(get("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Production Server"))
                .andExpect(jsonPath("$[0].apiKey").doesNotExist()); // Plaintext never leaked in list
    }

    @Test
    @DisplayName("Enforce active key limit of 10 keys per tenant (11th returns 409 API_KEY_LIMIT_REACHED)")
    void activeKeyLimitEnforced() throws Exception {
        TenantFixture fixture = createTenant("LimitTenant");

        for (int i = 1; i <= 10; i++) {
            CreateApiKeyRequest request = new CreateApiKeyRequest(
                    "Key #" + i,
                    List.of("notifications:send"),
                    null
            );
            mockMvc.perform(post("/api/v1/keys")
                            .header("Authorization", "Bearer " + fixture.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        // 11th key must fail
        CreateApiKeyRequest eleventh = new CreateApiKeyRequest(
                "Key #11",
                List.of("notifications:send"),
                null
        );
        mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eleventh)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("API_KEY_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("Cross-tenant isolation: Tenant B cannot revoke or rotate Tenant A's key (returns 404)")
    void crossTenantIsolation() throws Exception {
        TenantFixture tenantA = createTenant("TenantA");
        TenantFixture tenantB = createTenant("TenantB");

        // Tenant A creates key
        CreateApiKeyRequest request = new CreateApiKeyRequest("Key A", List.of("notifications:send"), null);
        MvcResult result = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + tenantA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String keyId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // Tenant B attempts delete -> 404
        mockMvc.perform(delete("/api/v1/keys/" + keyId)
                        .header("Authorization", "Bearer " + tenantB.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        // Tenant B attempts rotate -> 404
        mockMvc.perform(post("/api/v1/keys/" + keyId + "/rotate")
                        .header("Authorization", "Bearer " + tenantB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Revoke key immediately disables authentication with 401 INVALID_API_KEY")
    void revokeKeyDisablesAuth() throws Exception {
        TenantFixture fixture = createTenant("RevokeTenant");

        CreateApiKeyRequest request = new CreateApiKeyRequest("Key to Revoke", List.of("notifications:send"), null);
        MvcResult createRes = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String keyId = node.get("id").asText();
        String plaintext = node.get("apiKey").asText();

        // Verify key works before revocation
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authType").value("API_KEY"));

        // Revoke key
        mockMvc.perform(delete("/api/v1/keys/" + keyId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isNoContent());

        // Second revoke is idempotent
        mockMvc.perform(delete("/api/v1/keys/" + keyId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isNoContent());

        // Subsequent authentication attempt fails
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_API_KEY"));
    }

    @Test
    @DisplayName("Rotate key creates new key, links rotated_to_id, and keeps old key active for 24h overlap")
    void rotateKeyLifecycle() throws Exception {
        TenantFixture fixture = createTenant("RotateTenant");

        CreateApiKeyRequest request = new CreateApiKeyRequest("Key to Rotate", List.of("notifications:send"), null);
        MvcResult createRes = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String oldKeyId = node.get("id").asText();
        String oldPlaintext = node.get("apiKey").asText();

        // Rotate
        CreateApiKeyRequest rotateReq = new CreateApiKeyRequest("Rotated Key", List.of("notifications:send", "templates:read"), null);
        MvcResult rotateRes = mockMvc.perform(post("/api/v1/keys/" + oldKeyId + "/rotate")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rotateReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn();

        JsonNode rotateNode = objectMapper.readTree(rotateRes.getResponse().getContentAsString());
        String newPlaintext = rotateNode.get("apiKey").asText();
        String newKeyId = rotateNode.get("id").asText();

        // Both old and new keys work during the 24-hour overlap grace period
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", oldPlaintext))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", newPlaintext))
                .andExpect(status().isOk());

        // Verify rotated_to_id link and 24h grace expiry in DB
        ApiKeyEntity oldEntity = apiKeyRepository.findById(UUID.fromString(oldKeyId)).orElseThrow();
        assertThat(oldEntity.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(oldEntity.getRotatedToId()).isEqualTo(UUID.fromString(newKeyId));
        assertThat(oldEntity.getExpiresAt()).isNotNull();
        assertThat(oldEntity.getExpiresAt()).isAfter(Instant.now().plusSeconds(86000));

        // Attempting to rotate old key again fails with 409 INVALID_STATE_TRANSITION
        mockMvc.perform(post("/api/v1/keys/" + oldKeyId + "/rotate")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        // Explicitly revoking old key immediately disables it
        mockMvc.perform(delete("/api/v1/keys/" + oldKeyId)
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", oldPlaintext))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_API_KEY"));

        // New key continues to work
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", newPlaintext))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Rotation does not extend an already earlier expiry")
    void rotateDoesNotExtendEarlierExpiry() throws Exception {
        TenantFixture fixture = createTenant("ShortExpiryTenant");

        Instant earlierExpiry = Instant.now().plusSeconds(7200); // 2 hours
        String payload = """
                {
                    "name": "Short Lived Key",
                    "scopes": ["notifications:send"],
                    "expiresAt": "%s"
                }
                """.formatted(earlierExpiry.toString());
        MvcResult createRes = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String oldKeyId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        // Rotate key
        mockMvc.perform(post("/api/v1/keys/" + oldKeyId + "/rotate")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        ApiKeyEntity oldEntity = apiKeyRepository.findById(UUID.fromString(oldKeyId)).orElseThrow();
        // The old key's expiry must not have been extended to 24 hours; it should still be ~2 hours
        assertThat(oldEntity.getExpiresAt()).isBefore(Instant.now().plusSeconds(7300));
    }

    @Test
    @DisplayName("Expired API key is rejected with 401 API_KEY_EXPIRED")
    void expiredKeyRejection() throws Exception {
        TenantFixture fixture = createTenant("ExpireTenant");

        String plaintext = "ntf_expiredkeytesting1234567890abcdefghijk";
        String hash = ApiKeyService.sha256Hex(plaintext);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setTenantId(fixture.tenantId());
        entity.setName("Expired Key");
        entity.setKeyPrefix("ntf_expi");
        entity.setKeyHash(hash);
        entity.setScopes(new String[]{"notifications:send"});
        entity.setStatus(ApiKeyStatus.ACTIVE);
        entity.setExpiresAt(Instant.now().minusSeconds(3600)); // 1 hour ago
        apiKeyRepository.save(entity);

        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("API_KEY_EXPIRED"));
    }

    @Test
    @DisplayName("Tenant lifecycle status enforcement: SUSPENDED and REVOKED tenants reject API keys with 403")
    void tenantStatusMatrix() throws Exception {
        TenantFixture fixture = createTenant("StatusTenant");

        CreateApiKeyRequest request = new CreateApiKeyRequest("Status Key", List.of("notifications:send"), null);
        MvcResult res = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String plaintext = objectMapper.readTree(res.getResponse().getContentAsString()).get("apiKey").asText();

        // 1. Suspend tenant
        TenantEntity tenant = tenantRepository.findById(fixture.tenantId()).orElseThrow();
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);

        // API key auth must be rejected with 403 TENANT_SUSPENDED
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));

        // JWT read (GET /api/v1/keys) is allowed for suspended tenant
        mockMvc.perform(get("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isOk());

        // JWT write (POST /api/v1/keys) is blocked for suspended tenant
        mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest("Key2", List.of("notifications:send"), null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));

        // 2. Revoke tenant
        tenant.setStatus(TenantStatus.REVOKED);
        tenantRepository.save(tenant);

        // API key auth rejected with 403 TENANT_REVOKED
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_REVOKED"));
    }

    @Test
    @DisplayName("Scope enforcement: missing required scope results in 403 INSUFFICIENT_SCOPE")
    void scopeEnforcement() throws Exception {
        TenantFixture fixture = createTenant("ScopeTenant");

        CreateApiKeyRequest request = new CreateApiKeyRequest("Send Only Key", List.of("notifications:send"), null);
        MvcResult res = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String plaintext = objectMapper.readTree(res.getResponse().getContentAsString()).get("apiKey").asText();

        // Access to send scope works
        mockMvc.perform(get("/api/v1/test/send")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isOk());

        // Access to templates scope fails with 403 INSUFFICIENT_SCOPE
        mockMvc.perform(get("/api/v1/test/templates")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    @DisplayName("API key cannot manage API keys (JWT owner role required)")
    void apiKeyCannotManageKeys() throws Exception {
        TenantFixture fixture = createTenant("ManagementTenant");

        CreateApiKeyRequest request = new CreateApiKeyRequest("Key", List.of("notifications:send"), null);
        MvcResult res = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String plaintext = objectMapper.readTree(res.getResponse().getContentAsString()).get("apiKey").asText();

        // Try to list keys with API key instead of JWT
        mockMvc.perform(get("/api/v1/keys")
                        .header("X-API-Key", plaintext))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Authorization header with Bearer ntf_ prefix is supported")
    void authorizationBearerPrefixSupported() throws Exception {
        TenantFixture fixture = createTenant("BearerTenant");

        CreateApiKeyRequest request = new CreateApiKeyRequest("Bearer Key", List.of("notifications:send"), null);
        MvcResult res = mockMvc.perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String plaintext = objectMapper.readTree(res.getResponse().getContentAsString()).get("apiKey").asText();

        mockMvc.perform(get("/api/v1/test/send")
                        .header("Authorization", "Bearer " + plaintext))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authType").value("API_KEY"));
    }
}
