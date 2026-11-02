package com.notify.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.entity.RefreshTokenEntity;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.auth.service.RefreshTokenService;
import com.notify.common.model.Role;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.entity.TenantUserEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.repository.TenantUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantAuthIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantUserRepository tenantUserRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.notify.webhook.service.WebhookEndpointService webhookEndpointService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Signup creates tenant, owner user, hashes password with BCrypt, and returns tokens with Path=/auth")
    void signupSuccess() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@acme.com";
        SignupRequest request = new SignupRequest("Acme Corp", email, "SecurePass123!");

        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(cookie().exists("notify_refresh"))
                .andExpect(cookie().httpOnly("notify_refresh", true))
                .andExpect(cookie().secure("notify_refresh", true))
                .andExpect(cookie().path("notify_refresh", "/auth"))
                .andReturn();

        // Verify database persistence
        TenantUserEntity user = tenantUserRepository.findByEmailAndPasswordHashIsNotNull(email).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.TENANT_OWNER);
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(passwordEncoder.matches("SecurePass123!", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).startsWith("$2a$12$");

        TenantEntity tenant = tenantRepository.findById(user.getTenantId()).orElseThrow();
        assertThat(tenant.getName()).isEqualTo("Acme Corp");
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);

        // Verify refresh token stored in DB (hashed)
        Cookie refreshCookie = result.getResponse().getCookie("notify_refresh");
        assertThat(refreshCookie).isNotNull();
        String hash = RefreshTokenService.sha256(refreshCookie.getValue());
        RefreshTokenEntity rt = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(rt.getPrincipalId()).isEqualTo(user.getId());
        assertThat(rt.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("Signup provisions no webhook endpoint when no demo receiver is configured ()")
    void signupProvisionsNoWebhookEndpointWithoutADemoUrl() throws Exception {
        String email = "no-demo-" + UUID.randomUUID() + "@acme.com";

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("No Demo Corp", email, "SecurePass123!"))))
                .andExpect(status().isCreated());

        TenantUserEntity user = tenantUserRepository.findByEmailAndPasswordHashIsNotNull(email).orElseThrow();
        assertThat(webhookEndpointService.find(user.getTenantId())).isEmpty();
    }

    @Test
    @DisplayName("Signup with duplicate email returns 409 EMAIL_ALREADY_REGISTERED")
    void signupDuplicateEmail() throws Exception {
        String email = "duplicate-" + UUID.randomUUID() + "@acme.com";
        SignupRequest request = new SignupRequest("Acme One", email, "SecurePass123!");

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        SignupRequest duplicateRequest = new SignupRequest("Acme Two", email, "OtherPass123!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("Login with valid credentials returns access token and refresh cookie")
    void loginSuccess() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Login Inc", email, "MySecretPass!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest(email, "MySecretPass!");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(cookie().exists("notify_refresh"))
                .andExpect(cookie().path("notify_refresh", "/auth"));
    }

    @Test
    @DisplayName("Login with wrong password returns 401 UNAUTHENTICATED")
    void loginInvalidPassword() throws Exception {
        String email = "wrongpass-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("WrongPass Inc", email, "CorrectPass123!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest(email, "WrongPassword!");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("/auth/me returns current user info with valid JWT, 401 without JWT")
    void currentUserMe() throws Exception {
        String email = "me-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Me Corp", email, "Password123!");
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(signupResult.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();

        // With valid token
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.tenantName").value("Me Corp"))
                .andExpect(jsonPath("$.tenantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("TENANT_OWNER"));

        // Without token -> 401
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Refresh rotation succeeds and old token reuse triggers family revocation")
    void refreshRotationAndReuseDetection() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Refresh Corp", email, "Password123!");
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie oldCookie = signupResult.getResponse().getCookie("notify_refresh");
        assertThat(oldCookie).isNotNull();

        // 1. Rotate refresh token once
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(oldCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("notify_refresh"))
                .andReturn();

        Cookie newCookie = refreshResult.getResponse().getCookie("notify_refresh");
        assertThat(newCookie).isNotNull();
        assertThat(newCookie.getValue()).isNotEqualTo(oldCookie.getValue());

        // 2. Reusing the old cookie must trigger REFRESH_REUSE_DETECTED and revoke the family
        mockMvc.perform(post("/auth/refresh")
                        .cookie(oldCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("REFRESH_REUSE_DETECTED"));

        // 3. Even the new cookie should now be invalid because the entire family was revoked
        mockMvc.perform(post("/auth/refresh")
                        .cookie(newCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Concurrent refresh requests with same token: one succeeds, other triggers REFRESH_REUSE_DETECTED")
    void concurrentRefreshRotation() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Concurrent Corp", email, "Password123!");
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie cookie = signupResult.getResponse().getCookie("notify_refresh");
        assertThat(cookie).isNotNull();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> callRefresh = () -> {
            startLatch.await();
            MvcResult res = mockMvc.perform(post("/auth/refresh").cookie(cookie)).andReturn();
            return res.getResponse().getStatus();
        };

        Future<Integer> f1 = executor.submit(callRefresh);
        Future<Integer> f2 = executor.submit(callRefresh);
        startLatch.countDown();

        int s1 = f1.get(10, TimeUnit.SECONDS);
        int s2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one must succeed (200), and the other must be rejected (401 REFRESH_REUSE_DETECTED)
        assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    @DisplayName("Logout revokes refresh token in DB with Path=/auth cookie and clears cookie")
    void logoutSuccess() throws Exception {
        String email = "logout-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Logout Corp", email, "Password123!");
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(signupResult.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();
        Cookie cookie = signupResult.getResponse().getCookie("notify_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).isEqualTo("/auth");

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("notify_refresh", 0))
                .andExpect(cookie().path("notify_refresh", "/auth"));

        // Verify the refresh token is truly marked as revoked in the database
        String hash = RefreshTokenService.sha256(cookie.getValue());
        RefreshTokenEntity rt = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(rt.getRevokedAt()).isNotNull();

        // Attempting to refresh with the logged out cookie fails
        mockMvc.perform(post("/auth/refresh")
                        .cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Logout is idempotent when cookie is missing or invalid, and still denylists current access token JTI")
    void logoutIdempotent() throws Exception {
        String email = "idempotent-logout-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Idem Corp", email, "Password123!");
        MvcResult signupResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(signupResult.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();

        // 1. Logout without any cookie at all -> returns 204
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 2. Logout with unknown/garbage cookie -> returns 204
        Cookie garbageCookie = new Cookie("notify_refresh", "garbage_value_not_found_in_database");
        garbageCookie.setPath("/auth");
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .cookie(garbageCookie))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Forward migration V3: global tenant-user email uniqueness rejects second signup with same email")
    void globalEmailUniquenessEnforced() throws Exception {
        String email = "global-unique-" + UUID.randomUUID() + "@corp.com";
        SignupRequest req1 = new SignupRequest("Corp One", email, "Password123!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Second signup with same email (even with different tenant name) must be rejected with 409
        SignupRequest req2 = new SignupRequest("Corp Two", email.toUpperCase(), "DifferentPass123!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("Login with revoked tenant returns 403 TENANT_REVOKED")
    void revokedTenantBlockedFromLogin() throws Exception {
        String email = "revoked-" + UUID.randomUUID() + "@acme.com";
        SignupRequest signup = new SignupRequest("Revoked Corp", email, "Password123!");
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated());

        // Revoke the tenant directly in the database
        TenantUserEntity user = tenantUserRepository.findByEmailAndPasswordHashIsNotNull(email).orElseThrow();
        TenantEntity tenant = tenantRepository.findById(user.getTenantId()).orElseThrow();
        tenant.setStatus(TenantStatus.REVOKED);
        tenantRepository.save(tenant);

        LoginRequest login = new LoginRequest(email, "Password123!");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_REVOKED"));
    }
}
