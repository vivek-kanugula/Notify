package com.notify.auth.security;

import com.notify.config.NotifyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityCorsConfigTest {

    @Test
    @DisplayName("CORS allow-list includes X-Confirm-Tenant-Slug for cross-origin browser deletion preflight")
    void corsAllowedHeadersIncludesConfirmTenantSlug() {
        NotifyProperties properties = new NotifyProperties(
                "all",
                "http://localhost:8080",
                "http://localhost:5173",
                null
        );

        SecurityConfig securityConfig = new SecurityConfig(null, null, null, properties);
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/tenants/00000000-0000-0000-0000-000000000001");
        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowedHeaders()).contains("X-Confirm-Tenant-Slug");
        assertThat(config.getAllowedHeaders()).contains("Authorization");
        assertThat(config.getAllowedMethods()).contains("DELETE", "PATCH", "POST", "GET", "OPTIONS");
        assertThat(config.getAllowCredentials()).isTrue();
    }
}
