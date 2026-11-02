package com.notify.auth.dto;

/**
 * Authentication response returned on login/signup/refresh.
 * Contains only the access token (kept in SPA memory) and expiry.
 * Refresh token is set in HttpOnly cookie by the controller.
 * Never exposes jti or secrets.
 */
public record AuthResponse(
        String accessToken,
        long expiresIn,
        String tokenType
) {
    public AuthResponse(String accessToken, long expiresIn) {
        this(accessToken, expiresIn, "Bearer");
    }
}
