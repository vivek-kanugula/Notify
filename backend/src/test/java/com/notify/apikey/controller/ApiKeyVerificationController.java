package com.notify.apikey.controller;

import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Verification endpoint for API key authentication and scope enforcement in test environment.
 * Placed in test sources to ensure it is never exposed in production.
 */
@RestController
@RequestMapping("/api/v1/test")
public class ApiKeyVerificationController {

    @GetMapping("/send")
    @PreAuthorize("hasAuthority('SCOPE_notifications:send') or hasRole('TENANT_OWNER')")
    public ResponseEntity<Map<String, Object>> testSendScope() {
        TenantPrincipal principal = TenantContext.require();
        return ResponseEntity.ok(Map.of(
                "tenantId", principal.tenantId().toString(),
                "authType", principal.authType(),
                "scopes", principal.scopes()
        ));
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('SCOPE_templates:read') or hasRole('TENANT_OWNER')")
    public ResponseEntity<Map<String, Object>> testTemplatesScope() {
        TenantPrincipal principal = TenantContext.require();
        return ResponseEntity.ok(Map.of(
                "tenantId", principal.tenantId().toString(),
                "authType", principal.authType(),
                "scopes", principal.scopes()
        ));
    }
}
