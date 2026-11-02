package com.notify.apikey.controller;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.apikey.dto.ApiKeyCreatedResponse;
import com.notify.apikey.dto.ApiKeyResponse;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.dto.RotateApiKeyRequest;
import com.notify.apikey.service.ApiKeyService;
import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.TenantStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Tenant API key management endpoints.
 *
 * All endpoints require tenant owner JWT authentication (never API key auth).
 * Enforces tenant status:
 * - ACTIVE: full access
 * - SUSPENDED: GET permitted (read-only); POST/DELETE blocked with 403 TENANT_SUSPENDED
 * - REVOKED: blocked
 */
@RestController
@RequestMapping("/api/v1/keys")
@PreAuthorize("hasRole('TENANT_OWNER')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyStatusCacheService cacheService;

    public ApiKeyController(ApiKeyService apiKeyService, ApiKeyStatusCacheService cacheService) {
        this.apiKeyService = apiKeyService;
        this.cacheService = cacheService;
    }

    /**
     * List all API keys for the current tenant.
     * Never returns plaintext keys.
     */
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantNotRevoked(principal.tenantId());
        return ResponseEntity.ok(apiKeyService.listKeys(principal.tenantId()));
    }

    /**
     * Create a new scoped API key.
     * Returns 201 with the plaintext key revealed once.
     */
    @PostMapping
    public ResponseEntity<ApiKeyCreatedResponse> createKey(@Valid @RequestBody CreateApiKeyRequest request) {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());
        ApiKeyCreatedResponse response = apiKeyService.createKey(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Revoke an API key by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeKey(@PathVariable("id") UUID id) {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());
        apiKeyService.revokeKey(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Rotate an API key: creates a new key with inherited/overridden scopes and sets the old key's expiry to the grace period.
     * Returns 201 with the new plaintext key revealed once.
     */
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiKeyCreatedResponse> rotateKey(
            @PathVariable("id") UUID id,
            @RequestBody(required = false) RotateApiKeyRequest request) {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());
        RotateApiKeyRequest effectiveRequest = request != null ? request : new RotateApiKeyRequest(null, null, null, null);
        ApiKeyCreatedResponse response = apiKeyService.rotateKey(principal.tenantId(), id, effectiveRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private TenantPrincipal requireJwtPrincipal() {
        TenantPrincipal principal = TenantContext.require();
        if (!"JWT".equals(principal.authType())) {
            throw new NotifyException(ErrorCode.FORBIDDEN, "API key management requires JWT authentication");
        }
        return principal;
    }

    private void assertTenantWritable(UUID tenantId) {
        TenantStatus status = cacheService.getTenantStatus(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));

        if (status == TenantStatus.SUSPENDED) {
            throw new NotifyException(ErrorCode.TENANT_SUSPENDED, "Tenant is suspended (read-only mode)");
        }
        if (status == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant is revoked");
        }
    }

    private void assertTenantNotRevoked(UUID tenantId) {
        TenantStatus status = cacheService.getTenantStatus(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));

        if (status == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant is revoked");
        }
    }
}
