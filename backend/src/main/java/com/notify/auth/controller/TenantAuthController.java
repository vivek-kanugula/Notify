package com.notify.auth.controller;

import com.notify.auth.dto.AuthResponse;
import com.notify.auth.dto.CurrentUserResponse;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.*;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.PrincipalType;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant authentication controller: signup, login, refresh, logout, /me.
 * Access JWT returned in response body (SPA keeps in memory).
 * Refresh token only in HttpOnly SameSite=Strict Secure cookie.
 */
@RestController
@RequestMapping("/auth")
public class TenantAuthController {

    private static final String REFRESH_COOKIE_NAME = "notify_refresh";
    private static final String REFRESH_COOKIE_PATH = "/auth";
    private static final int REFRESH_COOKIE_MAX_AGE = (int) Duration.ofDays(7).getSeconds();

    private final TenantAuthService tenantAuthService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final JwtDenylistService jwtDenylistService;
    private final TenantRepository tenantRepository;

    public TenantAuthController(TenantAuthService tenantAuthService,
                                 RefreshTokenService refreshTokenService,
                                 JwtService jwtService,
                                 JwtDenylistService jwtDenylistService,
                                 TenantRepository tenantRepository) {
        this.tenantAuthService = tenantAuthService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.jwtDenylistService = jwtDenylistService;
        this.tenantRepository = tenantRepository;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        String userAgent = httpRequest.getHeader("User-Agent");
        String ip = httpRequest.getRemoteAddr();

        TenantAuthService.SignupResult result = tenantAuthService.signup(request, userAgent, ip);

        setRefreshCookie(httpResponse, result.refreshResult().rawToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(result.authResponse());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        String userAgent = httpRequest.getHeader("User-Agent");
        String ip = httpRequest.getRemoteAddr();

        TenantAuthService.LoginResult result = tenantAuthService.login(request, userAgent, ip);

        setRefreshCookie(httpResponse, result.refreshResult().rawToken());
        return ResponseEntity.ok(result.authResponse());
    }

    /**
     * Refresh rotates on the refresh cookie and returns a new access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request,
                                                 HttpServletResponse response) {
        String userAgent = request.getHeader("User-Agent");
        String ip = request.getRemoteAddr();

        String rawToken = extractRefreshToken(request);
        if (rawToken == null) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "No refresh token provided");
        }
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotateRefreshToken(
                rawToken, PrincipalType.TENANT_USER, userAgent, ip);
        AuthResponse authResponse = tenantAccessTokenOrReject(rotation);
        setRefreshCookie(response, rotation.refreshResult().rawToken());
        return ResponseEntity.ok(authResponse);
    }

    private AuthResponse tenantAccessTokenOrReject(RefreshTokenService.RotationResult rotation) {
        if (rotation.principalType() != PrincipalType.TENANT_USER) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Use /auth/admin/refresh for admin tokens");
        }
        return generateTenantAccessToken(rotation.principalId());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) {
        // Revoke refresh token
        String rawToken = extractRefreshToken(request);
        if (rawToken != null) {
            refreshTokenService.revokeByRawToken(rawToken);
        }

        // Add current access token's JTI to denylist
        if (authentication != null && authentication.getPrincipal() instanceof Map<?, ?> principal) {
            String jti = (String) principal.get("jti");
            if (jti != null) {
                jwtDenylistService.denyJti(jti, jwtService.getAccessTokenTtl());
            }
        }

        // Clear refresh cookie
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Map<?, ?> principal)) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }

        String audience = (String) principal.get("aud");
        if (!"notify-tenant".equals(audience)) {
            throw new NotifyException(ErrorCode.FORBIDDEN, "Use /auth/admin/me for admin access");
        }

        UUID userId = UUID.fromString((String) principal.get("sub"));
        CurrentUserResponse currentUser = tenantAuthService.getCurrentUser(userId);

        // Enforce tenant status on /me — SUSPENDED allows reads
        // REVOKED returns forbidden
        if ("REVOKED".equals(currentUser.tenantStatus())) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant account has been revoked");
        }

        return ResponseEntity.ok(currentUser);
    }

    private AuthResponse generateTenantAccessToken(UUID userId) {
        CurrentUserResponse user = tenantAuthService.getCurrentUser(userId);

        // Check tenant status
        TenantEntity tenant = tenantRepository.findById(user.tenantId())
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "Tenant not found"));
        if (tenant.getStatus() == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant account has been revoked");
        }

        JwtService.TokenResult accessToken = jwtService.generateTenantToken(
                user.userId(), user.tenantId(), user.roles());
        return new AuthResponse(accessToken.token(), jwtService.getAccessTokenTtl().getSeconds());
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, rawToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath(REFRESH_COOKIE_PATH);
        cookie.setMaxAge(REFRESH_COOKIE_MAX_AGE);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath(REFRESH_COOKIE_PATH);
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    private String extractRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
