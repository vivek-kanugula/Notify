package com.notify.auth;

import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.auth.service.LoginThrottleService;
import com.notify.auth.service.RefreshTokenService;
import com.notify.auth.service.TenantAuthService;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Role;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.entity.TenantUserEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.repository.TenantUserRepository;
import com.notify.webhook.service.WebhookEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantAuthEmailValidationTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantUserRepository tenantUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private LoginThrottleService loginThrottleService;
    @Mock private WebhookEndpointService webhookEndpointService;

    private TenantAuthService serviceWithGmailEnforced;
    private TenantAuthService serviceWithGmailDisabled;

    @BeforeEach
    void setUp() {
        serviceWithGmailEnforced = new TenantAuthService(
                tenantRepository, tenantUserRepository, passwordEncoder,
                jwtService, refreshTokenService, loginThrottleService,
                webhookEndpointService, true
        );

        serviceWithGmailDisabled = new TenantAuthService(
                tenantRepository, tenantUserRepository, passwordEncoder,
                jwtService, refreshTokenService, loginThrottleService,
                webhookEndpointService, false
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "test@gmail.com",
            "first.last@gmail.com",
            "user+tag@gmail.com",
            "USER@GMAIL.COM",
            "u.123_45-6@gmail.com",
            "  spaced@gmail.com  "
    })
    @DisplayName("isGoogleEmail returns true for valid @gmail.com addresses")
    void isGoogleEmail_valid(String email) {
        assertThat(TenantAuthService.isGoogleEmail(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "user@yahoo.com",
            "user@outlook.com",
            "user@google.com",
            "user@gmail.com.co",
            "user@notgmail.com",
            "user@gmail.org",
            "plainaddress",
            "@gmail.com",
            "user@",
            "",
            "   "
    })
    @DisplayName("isGoogleEmail returns false for non-gmail or invalid addresses")
    void isGoogleEmail_invalid(String email) {
        assertThat(TenantAuthService.isGoogleEmail(email)).isFalse();
    }

    @Test
    @DisplayName("signup with non-gmail email throws VALIDATION_FAILED when requireGmail is true")
    void signup_nonGmailRejected_whenEnforced() {
        SignupRequest request = new SignupRequest("Acme Corp", "user@yahoo.com", "Password123!");

        assertThatThrownBy(() -> serviceWithGmailEnforced.signup(request, "agent", "127.0.0.1"))
                .isInstanceOf(NotifyException.class)
                .hasMessageContaining("Email must be a valid Google domain (@gmail.com)")
                .satisfies(e -> assertThat(((NotifyException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(tenantRepository);
        verifyNoInteractions(tenantUserRepository);
    }

    @Test
    @DisplayName("signup with valid @gmail.com succeeds when requireGmail is true")
    void signup_validGmailAccepted_whenEnforced() {
        SignupRequest request = new SignupRequest("Acme Corp", "user@gmail.com", "Password123!");

        when(tenantUserRepository.findByEmail("user@gmail.com")).thenReturn(Optional.empty());
        TenantEntity mockTenant = new TenantEntity("Acme Corp", "acme-corp");
        when(tenantRepository.saveAndFlush(any(TenantEntity.class))).thenReturn(mockTenant);

        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashed");
        TenantUserEntity mockUser = new TenantUserEntity();
        mockUser.setId(UUID.randomUUID());
        mockUser.setTenantId(mockTenant.getId());
        mockUser.setRole(Role.TENANT_OWNER);
        when(tenantUserRepository.saveAndFlush(any(TenantUserEntity.class))).thenReturn(mockUser);

        when(jwtService.generateTenantToken(any(), any(), any()))
                .thenReturn(new JwtService.TokenResult("access-token-123", "jti-1", java.time.Instant.now().plusSeconds(900)));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofSeconds(900));

        when(refreshTokenService.createRefreshToken(any(), any(), any(), any()))
                .thenReturn(new RefreshTokenService.RefreshResult(UUID.randomUUID(), "raw-refresh-token", java.time.Instant.now().plusSeconds(604800), UUID.randomUUID()));

        var result = serviceWithGmailEnforced.signup(request, "agent", "127.0.0.1");

        assertThat(result).isNotNull();
        assertThat(result.authResponse().accessToken()).isEqualTo("access-token-123");
        verify(tenantRepository).saveAndFlush(any(TenantEntity.class));
        verify(tenantUserRepository).saveAndFlush(any(TenantUserEntity.class));
    }

    @Test
    @DisplayName("signup with non-gmail email is permitted when requireGmail is false")
    void signup_nonGmailAllowed_whenDisabled() {
        SignupRequest request = new SignupRequest("Acme Corp", "user@acme.com", "Password123!");

        when(tenantUserRepository.findByEmail("user@acme.com")).thenReturn(Optional.empty());
        TenantEntity mockTenant = new TenantEntity("Acme Corp", "acme-corp");
        when(tenantRepository.saveAndFlush(any(TenantEntity.class))).thenReturn(mockTenant);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashed");

        TenantUserEntity mockUser = new TenantUserEntity();
        mockUser.setId(UUID.randomUUID());
        mockUser.setTenantId(mockTenant.getId());
        mockUser.setRole(Role.TENANT_OWNER);
        when(tenantUserRepository.saveAndFlush(any(TenantUserEntity.class))).thenReturn(mockUser);

        when(jwtService.generateTenantToken(any(), any(), any()))
                .thenReturn(new JwtService.TokenResult("token", "jti", java.time.Instant.now().plusSeconds(900)));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofSeconds(900));
        when(refreshTokenService.createRefreshToken(any(), any(), any(), any()))
                .thenReturn(new RefreshTokenService.RefreshResult(UUID.randomUUID(), "refresh", java.time.Instant.now().plusSeconds(604800), UUID.randomUUID()));

        var result = serviceWithGmailDisabled.signup(request, "agent", "127.0.0.1");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("login with existing non-gmail account succeeds even when requireGmail is true")
    void login_existingNonGmailAllowed_whenEnforced() {
        LoginRequest request = new LoginRequest("user@outlook.com", "Password123!");

        when(loginThrottleService.isThrottled("user@outlook.com", "127.0.0.1")).thenReturn(false);

        UUID tenantId = UUID.randomUUID();
        TenantUserEntity user = new TenantUserEntity();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setPasswordHash("$2a$12$hashed");
        user.setRole(Role.TENANT_OWNER);
        when(tenantUserRepository.findByEmail("user@outlook.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "$2a$12$hashed")).thenReturn(true);

        TenantEntity tenant = new TenantEntity("Acme Corp", "acme-corp");
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        when(jwtService.generateTenantToken(any(), any(), any()))
                .thenReturn(new JwtService.TokenResult("legacy-login-token", "jti", java.time.Instant.now().plusSeconds(900)));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofSeconds(900));
        when(refreshTokenService.createRefreshToken(any(), any(), any(), any()))
                .thenReturn(new RefreshTokenService.RefreshResult(UUID.randomUUID(), "login-refresh", java.time.Instant.now().plusSeconds(604800), UUID.randomUUID()));

        var result = serviceWithGmailEnforced.login(request, "agent", "127.0.0.1");
        assertThat(result).isNotNull();
        assertThat(result.authResponse().accessToken()).isEqualTo("legacy-login-token");
    }

    @Test
    @DisplayName("login with valid @gmail.com email proceeds when requireGmail is true")
    void login_validGmailAccepted_whenEnforced() {
        LoginRequest request = new LoginRequest("user@gmail.com", "Password123!");

        when(loginThrottleService.isThrottled("user@gmail.com", "127.0.0.1")).thenReturn(false);

        UUID tenantId = UUID.randomUUID();
        TenantUserEntity user = new TenantUserEntity();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setPasswordHash("$2a$12$hashed");
        user.setRole(Role.TENANT_OWNER);
        when(tenantUserRepository.findByEmail("user@gmail.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "$2a$12$hashed")).thenReturn(true);

        TenantEntity tenant = new TenantEntity("Acme Corp", "acme-corp");
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        when(jwtService.generateTenantToken(any(), any(), any()))
                .thenReturn(new JwtService.TokenResult("login-token", "jti", java.time.Instant.now().plusSeconds(900)));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofSeconds(900));
        when(refreshTokenService.createRefreshToken(any(), any(), any(), any()))
                .thenReturn(new RefreshTokenService.RefreshResult(UUID.randomUUID(), "login-refresh", java.time.Instant.now().plusSeconds(604800), UUID.randomUUID()));

        var result = serviceWithGmailEnforced.login(request, "agent", "127.0.0.1");
        assertThat(result).isNotNull();
        assertThat(result.authResponse().accessToken()).isEqualTo("login-token");
    }
}
