package com.notify.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M5: /actuator/prometheus is admin-only; liveness/readiness stay public. */
@AutoConfigureMetrics
class ActuatorSecurityTest extends AbstractPostgresIntegrationTest {

    @Autowired private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String adminToken() throws Exception {
        LoginRequest login = new LoginRequest("admin@notify.local", "placeholder_admin_password_min_10_chars");
        MvcResult r = mockMvc.perform(post("/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String tenantToken() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest("ActuatorCorp", "actuator-" + suffix + "@notify.local", "Password123!");
        MvcResult r = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("prometheus endpoint rejects anonymous and tenant callers, allows admin")
    void prometheusAdminOnly() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + tenantToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + com.notify.auth.service.JwtService.PROMETHEUS_SCRAPE_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("liveness and readiness probes stay public")
    void probesPublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
