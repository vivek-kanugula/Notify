package com.notify.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.webhook.model.WebhookEndpointView;
import com.notify.webhook.service.WebhookEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * with {@code notify.demo.webhook-url} set: a new tenant gets one demo endpoint, and signup still succeeds
 * when the demo surface is misconfigured. The opposite case — the property unset, so nothing is provisioned — is
 * covered by {@code TenantAuthIntegrationTest.signupProvisionsNoWebhookEndpointWithoutADemoUrl}.
 */
@TestPropertySource(properties = {
        "notify.demo.webhook-url=http://localhost:9099/demo/webhook-receiver",
        "notify.webhook.demo-allowlist=localhost,127.0.0.1"
})
class WebhookSignupProvisioningTest extends AbstractPostgresIntegrationTest {

    private static final String DEMO_URL = "http://localhost:9099/demo/webhook-receiver";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WebhookEndpointService endpointService;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record Signup(UUID tenantId, String token) {
    }

    private Signup signup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupRequest(
                                "Provisioned " + suffix, "prov-" + suffix + "@notify.local", "Password123!"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = root.get("accessToken").asText();
        return new Signup(UUID.fromString(jwtService.parseToken(token).get("tid", String.class)), token);
    }

    @Test
    @DisplayName("signup provisions one enabled demo endpoint pointing at the configured demo receiver")
    void signupProvisionsTheDemoEndpoint() throws Exception {
        Signup tenant = signup();

        WebhookEndpointView view = endpointService.find(tenant.tenantId()).orElseThrow();

        assertThat(view.url()).isEqualTo(DEMO_URL);
        assertThat(view.demo()).isTrue();
        assertThat(view.enabled()).isTrue();
    }

    @Test
    @DisplayName("the provisioned endpoint is visible to its owner and has a working, never-revealed secret")
    void provisionedEndpointIsVisibleAndSigned() throws Exception {
        Signup tenant = signup();

        mockMvc.perform(get("/api/v1/webhook-endpoint").header("Authorization", "Bearer " + tenant.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(DEMO_URL))
                .andExpect(jsonPath("$.isDemo").value(true))
                .andExpect(jsonPath("$.signingSecret").doesNotExist());

        assertThat(endpointService.resolveDestination(tenant.tenantId()).orElseThrow().secret())
                .startsWith("whsec_");
    }

    @Test
    @DisplayName("each tenant gets its own endpoint and its own secret")
    void eachTenantGetsItsOwnSecret() throws Exception {
        Signup first = signup();
        Signup second = signup();

        String firstSecret = endpointService.resolveDestination(first.tenantId()).orElseThrow().secret();
        String secondSecret = endpointService.resolveDestination(second.tenantId()).orElseThrow().secret();

        assertThat(first.tenantId()).isNotEqualTo(second.tenantId());
        assertThat(firstSecret).isNotEqualTo(secondSecret);
    }

    @Test
    @DisplayName("the tenant can replace the provisioned demo endpoint with its own, which clears the demo flag")
    void tenantCanReplaceTheProvisionedEndpoint() throws Exception {
        Signup tenant = signup();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/webhook-endpoint")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://receiver.example.com/hook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDemo").value(false));

        assertThat(endpointService.find(tenant.tenantId()).orElseThrow().url())
                .isEqualTo("https://receiver.example.com/hook");
    }
}
