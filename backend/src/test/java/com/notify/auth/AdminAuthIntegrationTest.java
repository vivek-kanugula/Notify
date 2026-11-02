package com.notify.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAuthIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Platform admin seeded by AdminBootstrap can log in with default credentials")
    void adminLoginSuccess() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");

        MvcResult result = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(cookie().exists("notify_admin_refresh"))
                .andExpect(cookie().httpOnly("notify_admin_refresh", true))
                .andExpect(cookie().secure("notify_admin_refresh", true))
                .andExpect(cookie().path("notify_admin_refresh", "/auth"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("accessToken").asText();

        // Verify JWT claims: audience = notify-admin, role = PLATFORM_ADMIN, no tid
        Claims claims = jwtService.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getAudience()).contains("notify-admin");
        assertThat(claims.get("tid")).isNull();
        assertThat(claims.get("roles", List.class)).contains("PLATFORM_ADMIN");
    }

    @Test
    @DisplayName("Admin /auth/admin/me returns admin info")
    void adminMeSuccess() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult loginResult = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/auth/admin/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@notify.local"))
                .andExpect(jsonPath("$.roles[0]").value("PLATFORM_ADMIN"));
    }

    @Test
    @DisplayName("Strict audience separation: cross-audience tokens return 403 Forbidden")
    void audienceSeparationEnforced() throws Exception {
        // 1. Get an admin token
        LoginRequest adminLogin = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult adminResult = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminLogin)))
                .andExpect(status().isOk())
                .andReturn();
        String adminToken = objectMapper.readTree(adminResult.getResponse().getContentAsString()).get("accessToken").asText();

        // 2. Get a tenant token
        String tenantEmail = "tenant-aud-" + UUID.randomUUID() + "@corp.com";
        SignupRequest signup = new SignupRequest("Aud Corp", tenantEmail, "Pass12345!");
        MvcResult tenantResult = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated())
                .andReturn();
        String tenantToken = objectMapper.readTree(tenantResult.getResponse().getContentAsString()).get("accessToken").asText();

        // Admin token cannot access tenant /auth/me -> 403
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // Tenant token cannot access admin /auth/admin/me -> 403
        mockMvc.perform(get("/auth/admin/me")
                        .header("Authorization", "Bearer " + tenantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // Tenant token cannot access /api/v1/admin/** -> 403
        mockMvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", "Bearer " + tenantToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // Admin token cannot access /api/v1/** (tenant endpoints) -> 403
        mockMvc.perform(get("/api/v1/templates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Admin logout revokes refresh token and clears cookie")
    void adminLogoutSuccess() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult loginResult = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie cookie = loginResult.getResponse().getCookie("notify_admin_refresh");

        mockMvc.perform(post("/auth/admin/logout")
                        .header("Authorization", "Bearer " + token)
                        .cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("notify_admin_refresh", 0))
                .andExpect(cookie().path("notify_admin_refresh", "/auth"));
    }

    @Test
    @DisplayName("Admin refresh endpoint allows unauthenticated call with valid refresh cookie")
    void adminRefreshUnauthenticated() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult loginResult = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie cookie = loginResult.getResponse().getCookie("notify_admin_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).isEqualTo("/auth");

        // Call /auth/admin/refresh with ONLY the cookie, no Bearer token
        mockMvc.perform(post("/auth/admin/refresh")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("notify_admin_refresh"))
                .andExpect(cookie().path("notify_admin_refresh", "/auth"));
    }
}
