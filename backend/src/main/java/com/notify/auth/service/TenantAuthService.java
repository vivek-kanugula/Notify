package com.notify.auth.service;

import com.notify.auth.dto.AuthResponse;
import com.notify.auth.dto.CurrentUserResponse;
import com.notify.auth.dto.LoginRequest;
import com.notify.auth.dto.SignupRequest;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.PrincipalType;
import com.notify.common.model.Role;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.entity.TenantUserEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.repository.TenantUserRepository;
import com.notify.webhook.service.WebhookEndpointService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant authentication service: signup, login, refresh, logout, /me.
 * Tenant identity comes exclusively from verified credentials per AGENTS.md.
 */
@Service
public class TenantAuthService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[^a-z0-9-]");
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$e8k4Vv5gX9T61p0u3e5sHeO1f0Vq8w3m7yK2r9t4w8x1z5v6p7q8r";

    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginThrottleService loginThrottleService;
    private final WebhookEndpointService webhookEndpointService;

    public TenantAuthService(
            TenantRepository tenantRepository,
            TenantUserRepository tenantUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            LoginThrottleService loginThrottleService,
            WebhookEndpointService webhookEndpointService
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginThrottleService = loginThrottleService;
        this.webhookEndpointService = webhookEndpointService;
    }

    /**
     * Tenant signup: creates tenant + owner user atomically.
     */
    @Transactional
    public SignupResult signup(SignupRequest request, String userAgent, String ipAddress) {
        // One account per email, across tenants.
        if (tenantUserRepository.findByEmail(request.email().toLowerCase(Locale.ROOT)).isPresent()) {
            throw new NotifyException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists");
        }

        // Create tenant (with slug collision retry for concurrent signups)
        String slug = generateSlug(request.tenantName());
        TenantEntity tenant = new TenantEntity(request.tenantName(), slug);
        try {
            tenant = tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            // Slug collision under concurrency: append random hex suffix
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 6);
            tenant = new TenantEntity(request.tenantName(), slug);
            tenant = tenantRepository.saveAndFlush(tenant);
        }

        // Create owner user
        TenantUserEntity user = new TenantUserEntity();
        user.setTenantId(tenant.getId());
        user.setEmail(request.email().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.TENANT_OWNER);
        user.setEmailVerified(true); // Direct signup = verified
        try {
            user = tenantUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Either unique constraint can fire here: uq_tenant_users_tenant_email, or the V8 partial index
            // uq_tenant_users_password_email when two signups for one email race past the pre-check above. Both
            // mean the same thing to the caller, so both map to the existing 409 and the exception detail - index
            // name, SQL state, statement text - is deliberately not propagated. The whole signup transaction
            // rolls back with this throw, so the losing request leaves no tenant row and no provisioned endpoint.
            throw new NotifyException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists");
        }

        // a default demo webhook endpoint, only when notify.demo.webhook-url is configured. The service is
        // deliberately quiet about a demo misconfiguration, so signup cannot fail because of the demo surface.
        webhookEndpointService.provisionDefault(tenant.getId());

        // Generate tokens
        JwtService.TokenResult accessToken = jwtService.generateTenantToken(
                user.getId(), tenant.getId(), List.of(Role.TENANT_OWNER.name()));
        RefreshTokenService.RefreshResult refreshResult = refreshTokenService.createRefreshToken(
                PrincipalType.TENANT_USER, user.getId(), userAgent, ipAddress);

        AuthResponse response = new AuthResponse(accessToken.token(), jwtService.getAccessTokenTtl().getSeconds());
        return new SignupResult(response, refreshResult, accessToken.jti());
    }

    /**
     * Tenant login with email/password.
     */
    @Transactional
    public LoginResult login(LoginRequest request, String userAgent, String ipAddress) {
        // Check throttle
        if (loginThrottleService.isThrottled(request.email(), ipAddress)) {
            throw new NotifyException(ErrorCode.RATE_LIMITED, "Too many login attempts. Please try again later.");
        }

        // Find the account for this email.
        TenantUserEntity user = tenantUserRepository
                .findByEmail(request.email().toLowerCase(Locale.ROOT))
                .orElse(null);

        String hashToMatch = (user != null && user.getPasswordHash() != null)
                ? user.getPasswordHash()
                : DUMMY_BCRYPT_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToMatch);

        if (user == null || user.getPasswordHash() == null || !passwordMatches) {
            loginThrottleService.recordFailure(request.email(), ipAddress);
            throw new NotifyException(ErrorCode.UNAUTHENTICATED, "Invalid email or password");
        }

        // Check tenant status — REVOKED blocks login
        TenantEntity tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "Invalid email or password"));

        if (tenant.getStatus() == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED,
                    "Tenant account has been revoked. Contact support.");
        }

        // Clear throttle on success
        loginThrottleService.clearFailures(request.email(), ipAddress);

        // Generate tokens
        JwtService.TokenResult accessToken = jwtService.generateTenantToken(
                user.getId(), tenant.getId(), List.of(user.getRole().name()));
        RefreshTokenService.RefreshResult refreshResult = refreshTokenService.createRefreshToken(
                PrincipalType.TENANT_USER, user.getId(), userAgent, ipAddress);

        AuthResponse response = new AuthResponse(accessToken.token(), jwtService.getAccessTokenTtl().getSeconds());
        return new LoginResult(response, refreshResult, accessToken.jti());
    }

    /**
     * Get current tenant user info for /me endpoint.
     */
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        TenantUserEntity user = tenantUserRepository.findById(userId)
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "User not found"));

        TenantEntity tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "Tenant not found"));

        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getStatus().name(),
                List.of(user.getRole().name())
        );
    }

    private String generateSlug(String tenantName) {
        String base = Normalizer.normalize(tenantName, Normalizer.Form.NFD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
        base = SLUG_PATTERN.matcher(base).replaceAll("");

        if (base.isEmpty()) {
            base = "tenant";
        }
        if (base.length() > 50) {
            base = base.substring(0, 50);
        }

        String slug = base;
        int suffix = 1;
        while (tenantRepository.findBySlug(slug).isPresent()) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    public record SignupResult(AuthResponse authResponse, RefreshTokenService.RefreshResult refreshResult, String jti) {}
    public record LoginResult(AuthResponse authResponse, RefreshTokenService.RefreshResult refreshResult, String jti) {}
}
