package com.notify.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Channel;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JwtService jwtService;

    @MockitoSpyBean
    private RateLimitService rateLimitService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record TenantFixture(UUID tenantId, UUID userId, String token) {}

    private TenantFixture createTenant(String name) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name + " " + unique, "owner-" + unique + "@example.com", "Password123!");
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
    @DisplayName("Accepted notification returns standard X-RateLimit-* response headers")
    void acceptedNotification_returnsRateLimitHeaders() throws Exception {
        TenantFixture tenant = createTenant("HeadersTenant");

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Rate Limit Test",
                "Testing rate limit headers",
                null,
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    @DisplayName("Rate-limited tenant receives 429 RATE_LIMITED with Retry-After header")
    void rateLimitedTenant_returns429WithRetryAfter() throws Exception {
        TenantFixture tenant = createTenant("ExhaustedTenant");

        // Simulate token bucket exhaustion
        doThrow(new RateLimitException(45)).when(rateLimitService).consumeToken(tenant.tenantId());

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Quota Exhausted",
                "Should return 429",
                null,
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Approved behavior: verified matching replay does not deduct a rate limit token")
    void verifiedReplay_bypassesRateLimitDeduction_amd13() throws Exception {
        TenantFixture tenant = createTenant("Amd13Tenant");
        String idemKey = "amd13-key-" + UUID.randomUUID();

        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "amd13@example.com",
                "Subject",
                "Body",
                null,
                null,
                null,
                null,
                idemKey,
                null
        );

        // 1. First call: consumes a token
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replay"));

        verify(rateLimitService, times(1)).consumeToken(tenant.tenantId());

        // 2. Matching replay: must succeed with 202 and Idempotent-Replay: true, WITHOUT consuming another token
        mockMvc.perform(post("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tenant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"));

        // consumeToken should NOT have been called a second time
        verify(rateLimitService, times(1)).consumeToken(tenant.tenantId());
    }

    @Test
    @DisplayName("Security: Direct requests cannot bypass auth IP rate limiting by changing X-Forwarded-For")
    void authRateLimiting_directRequestsCannotBypassLimitByChangingXForwardedFor() throws Exception {
        String directIp = "198.51.100.99";

        // Request 1 with spoofed header X-Forwarded-For: 1.1.1.1
        mockMvc.perform(post("/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(directIp);
                            return request;
                        })
                        .header("X-Forwarded-For", "1.1.1.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hacker@example.com\",\"password\":\"wrong\"}"))
                .andReturn();

        // Must evaluate limit for the direct remoteAddr, NOT 1.1.1.1
        verify(rateLimitService).checkIpLimit(directIp, "/auth/login", 30);
        verify(rateLimitService, never()).checkIpLimit("1.1.1.1", "/auth/login", 30);

        // Request 2 with different spoofed header X-Forwarded-For: 2.2.2.2
        mockMvc.perform(post("/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(directIp);
                            return request;
                        })
                        .header("X-Forwarded-For", "2.2.2.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hacker@example.com\",\"password\":\"wrong\"}"))
                .andReturn();

        // Must still evaluate limit for direct remoteAddr
        verify(rateLimitService, times(2)).checkIpLimit(directIp, "/auth/login", 30);
        verify(rateLimitService, never()).checkIpLimit("2.2.2.2", "/auth/login", 30);
    }

    @Test
    @DisplayName("Security: Filter honors X-Forwarded-For ONLY when request arrives from an explicitly trusted proxy")
    void authRateLimiting_trustedProxyHonorsForwardedFor() {
        var filter = new com.notify.ratelimit.filter.AuthRateLimitFilter(
                rateLimitService,
                java.util.Set.of("10.0.0.1", "10.0.0.2")
        );

        // Direct request from untrusted IP -> uses remoteAddr
        var mockReqDirect = new org.springframework.mock.web.MockHttpServletRequest();
        mockReqDirect.setRemoteAddr("198.51.100.5");
        mockReqDirect.addHeader("X-Forwarded-For", "203.0.113.195");
        org.assertj.core.api.Assertions.assertThat(filter.resolveClientIp(mockReqDirect)).isEqualTo("198.51.100.5");

        // Request from trusted proxy -> uses X-Forwarded-For
        var mockReqProxy = new org.springframework.mock.web.MockHttpServletRequest();
        mockReqProxy.setRemoteAddr("10.0.0.1");
        mockReqProxy.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");
        org.assertj.core.api.Assertions.assertThat(filter.resolveClientIp(mockReqProxy)).isEqualTo("203.0.113.195");
    }
}
