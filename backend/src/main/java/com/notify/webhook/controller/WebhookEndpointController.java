package com.notify.webhook.controller;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.TenantStatus;
import com.notify.webhook.dto.UpdateWebhookEndpointRequest;
import com.notify.webhook.dto.WebhookEndpointCreatedResponse;
import com.notify.webhook.dto.WebhookEndpointResponse;
import com.notify.webhook.dto.WebhookTestResultResponse;
import com.notify.webhook.model.WebhookUpsertResult;
import com.notify.webhook.service.WebhookEndpointService;
import com.notify.webhook.service.WebhookPingSender;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant webhook endpoint configuration ().
 *
 * <p>Tenant owner JWT only, never API key authentication: these endpoints create and rotate a signing secret, which is
 * the same rule already applied to API-key management. Tenant status is enforced as elsewhere — a SUSPENDED tenant may
 * read but not change anything, a REVOKED tenant is blocked.
 *
 * <p>The signing secret is returned exactly once, when the endpoint is created or rotated. No read ever reveals it.
 */
@RestController
@RequestMapping("/api/v1/webhook-endpoint")
@PreAuthorize("hasRole('TENANT_OWNER')")
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;
    private final WebhookPingSender pingSender;
    private final ApiKeyStatusCacheService tenantStatusCache;

    public WebhookEndpointController(
            WebhookEndpointService endpointService,
            WebhookPingSender pingSender,
            ApiKeyStatusCacheService tenantStatusCache
    ) {
        this.endpointService = endpointService;
        this.pingSender = pingSender;
        this.tenantStatusCache = tenantStatusCache;
    }

    /** Current configuration. 404 when the tenant has not configured an endpoint yet. */
    @GetMapping
    public ResponseEntity<WebhookEndpointResponse> get() {
        TenantPrincipal principal = requireJwtPrincipal();
        return endpointService.find(principal.tenantId())
                .map(WebhookEndpointResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
    }

    /**
     * Creates or updates the endpoint. The first call returns 201 with the signing secret; later calls return 200
     * without it. A URL the destination policy rejects is 422 {@code VALIDATION_FAILED}.
     */
    @PutMapping
    public ResponseEntity<?> upsert(@Valid @RequestBody UpdateWebhookEndpointRequest request) {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());

        WebhookUpsertResult result = endpointService.upsert(principal.tenantId(), request.url(), request.enabled());
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(WebhookEndpointCreatedResponse.from(result.view(), result.secretOnce()));
        }
        return ResponseEntity.ok(WebhookEndpointResponse.from(result.view()));
    }

    /** Sends exactly one signed ping to the configured destination. Writes no notification, attempt or outbox row. */
    @PostMapping("/test")
    public ResponseEntity<WebhookTestResultResponse> test() {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());
        return ResponseEntity.ok(WebhookTestResultResponse.from(pingSender.sendTestPing(principal.tenantId())));
    }

    /** Replaces the signing secret and returns the new one once. The previous secret stops working immediately. */
    @PostMapping("/rotate-secret")
    public ResponseEntity<WebhookEndpointCreatedResponse> rotateSecret() {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());

        String secret = endpointService.rotateSecret(principal.tenantId());
        return endpointService.find(principal.tenantId())
                .map(view -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(WebhookEndpointCreatedResponse.from(view, secret)))
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "No webhook endpoint is configured"));
    }

    /** Permanently deletes the tenant's webhook endpoint from the database. */
    @DeleteMapping
    public ResponseEntity<Void> delete() {
        TenantPrincipal principal = requireJwtPrincipal();
        assertTenantWritable(principal.tenantId());
        endpointService.delete(principal.tenantId());
        return ResponseEntity.noContent().build();
    }

    private TenantPrincipal requireJwtPrincipal() {
        TenantPrincipal principal = TenantContext.require();
        if (!"JWT".equals(principal.authType())) {
            throw new NotifyException(ErrorCode.FORBIDDEN,
                    "Webhook endpoint management requires JWT authentication");
        }
        return principal;
    }

    private void assertTenantWritable(UUID tenantId) {
        TenantStatus status = tenantStatusCache.getTenantStatus(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));
        if (status == TenantStatus.SUSPENDED) {
            throw new NotifyException(ErrorCode.TENANT_SUSPENDED, "Tenant is suspended (read-only mode)");
        }
        if (status == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant is revoked");
        }
    }
}
