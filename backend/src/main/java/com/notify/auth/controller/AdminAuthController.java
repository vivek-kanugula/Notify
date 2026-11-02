package com.notify.auth.controller;

import com.notify.auth.dto.AuthResponse;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.service.*;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.PrincipalType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Admin authentication controller: separate login/refresh/logout/me flow.
 * Admin JWT uses audience "notify-admin" and is strictly separated from tenant JWTs.
 */
@RestController
@RequestMapping("/auth/admin")
public class AdminAuthController {

    private static final String REFRESH_COOKIE_NAME = "notify_admin_refresh";
    private static final String REFRESH_COOKIE_PATH = "/auth";
    private static final int REFRESH_COOKIE_MAX_AGE = (int) Duration.ofDays(7).getSeconds();

    private final AdminAuthService adminAuthService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final JwtDenylistService jwtDenylistService;

    public AdminAuthController(AdminAuthService adminAuthService,
                                RefreshTokenService refreshTokenService,
                                JwtService jwtService,
                                JwtDenylistService jwtDenylistService) {
        this.adminAuthService = adminAuthService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.jwtDenylistService = jwtDenylistService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        String userAgent = httpRequest.getHeader("User-Agent");
        String ip = httpRequest.getRemoteAddr();

        AdminAuthService.LoginResult result = adminAuthService.login(request, userAgent, ip);

        setRefreshCookie(httpResponse, result.refreshResult().rawToken());
        return ResponseEntity.ok(result.authResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request,
                                                 HttpServletResponse response) {
        String rawToken = extractRefreshToken(request);
        if (rawToken == null) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "No refresh token provided");
        }

        String userAgent = request.getHeader("User-Agent");
        String ip = request.getRemoteAddr();

        RefreshTokenService.RotationResult rotation = refreshTokenService.rotateRefreshToken(
                rawToken, PrincipalType.ADMIN, userAgent, ip);

        // Verify this is an admin principal
        if (rotation.principalType() != PrincipalType.ADMIN) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Use /auth/refresh for tenant tokens");
        }

        // Generate new admin access token
        AdminAuthService.AdminUserInfo adminInfo = adminAuthService.getAdminInfo(rotation.principalId());
        JwtService.TokenResult accessToken = jwtService.generateAdminToken(
                adminInfo.adminId(), adminInfo.roles());

        setRefreshCookie(response, rotation.refreshResult().rawToken());
        return ResponseEntity.ok(new AuthResponse(accessToken.token(), jwtService.getAccessTokenTtl().getSeconds()));
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

        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AdminAuthService.AdminUserInfo> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Map<?, ?> principal)) {
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }

        String audience = (String) principal.get("aud");
        if (!"notify-admin".equals(audience)) {
            throw new NotifyException(ErrorCode.FORBIDDEN, "Use /auth/me for tenant access");
        }

        UUID adminId = UUID.fromString((String) principal.get("sub"));
        AdminAuthService.AdminUserInfo adminInfo = adminAuthService.getAdminInfo(adminId);
        return ResponseEntity.ok(adminInfo);
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
