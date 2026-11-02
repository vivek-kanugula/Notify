package com.notify.auth.service;

import com.notify.admin.entity.AdminUserEntity;
import com.notify.admin.repository.AdminUserRepository;
import com.notify.auth.dto.AuthResponse;
import com.notify.auth.dto.LoginRequest;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.PrincipalType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin authentication service: login only (admin users are seeded via AdminBootstrap).
 * Separate flow from tenant auth. Admin JWT uses audience "notify-admin".
 */
@Service
public class AdminAuthService {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$e8k4Vv5gX9T61p0u3e5sHeO1f0Vq8w3m7yK2r9t4w8x1z5v6p7q8r";

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginThrottleService loginThrottleService;

    public AdminAuthService(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            LoginThrottleService loginThrottleService
    ) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginThrottleService = loginThrottleService;
    }

    /**
     * Admin login with email/password.
     */
    @Transactional
    public LoginResult login(LoginRequest request, String userAgent, String ipAddress) {
        // Check throttle
        if (loginThrottleService.isThrottled(request.email(), ipAddress)) {
            throw new NotifyException(ErrorCode.RATE_LIMITED, "Too many login attempts. Please try again later.");
        }

        AdminUserEntity admin = adminUserRepository.findByEmail(request.email().toLowerCase(Locale.ROOT))
                .orElse(null);

        String hashToMatch = (admin != null && admin.getPasswordHash() != null)
                ? admin.getPasswordHash()
                : DUMMY_BCRYPT_HASH;
        boolean matches = passwordEncoder.matches(request.password(), hashToMatch);

        if (admin == null || !matches) {
            loginThrottleService.recordFailure(request.email(), ipAddress);
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Invalid email or password");
        }

        // Clear throttle on success
        loginThrottleService.clearFailures(request.email(), ipAddress);

        // Generate tokens with admin audience
        JwtService.TokenResult accessToken = jwtService.generateAdminToken(
                admin.getId(), List.of(admin.getRole().name()));
        RefreshTokenService.RefreshResult refreshResult = refreshTokenService.createRefreshToken(
                PrincipalType.ADMIN, admin.getId(), userAgent, ipAddress);

        AuthResponse response = new AuthResponse(accessToken.token(), jwtService.getAccessTokenTtl().getSeconds());
        return new LoginResult(response, refreshResult, accessToken.jti(), admin.getId());
    }

    /**
     * Get admin user info for /me endpoint.
     */
    @Transactional(readOnly = true)
    public AdminUserInfo getAdminInfo(UUID adminId) {
        AdminUserEntity admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "Admin not found"));

        return new AdminUserInfo(admin.getId(), admin.getEmail(), List.of(admin.getRole().name()));
    }

    public record LoginResult(AuthResponse authResponse, RefreshTokenService.RefreshResult refreshResult, String jti, UUID adminId) {}
    public record AdminUserInfo(UUID adminId, String email, List<String> roles) {}
}
