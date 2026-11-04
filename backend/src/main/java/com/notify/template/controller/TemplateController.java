package com.notify.template.controller;

import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.template.dto.*;
import com.notify.template.service.TemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped template management and rendering endpoints.
 *
 * Supports authentication via Tenant Owner JWT or scoped API Key:
 * - Read operations require ROLE_TENANT_OWNER or SCOPE_templates:read
 * - Write operations require ROLE_TENANT_OWNER or SCOPE_templates:write
 */
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * Create a new template and initial draft version 1.
     */
    @PostMapping
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<TemplateResponse> createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        UUID tenantId = getRequiredTenantId();
        TemplateResponse response = templateService.createTemplate(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all active templates for the tenant.
     */
    @GetMapping
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:read')")
    public ResponseEntity<List<TemplateResponse>> listTemplates() {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.listTemplates(tenantId));
    }

    /**
     * Get template details with all versions.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:read')")
    public ResponseEntity<TemplateDetailResponse> getTemplate(@PathVariable UUID id) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.getTemplate(tenantId, id));
    }

    /**
     * Update an active template's name and/or description.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<TemplateResponse> updateTemplate(@PathVariable UUID id,
                                                           @Valid @RequestBody UpdateTemplateRequest request) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.updateTemplate(tenantId, id, request));
    }

    /**
     * Soft-delete a template.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID id) {
        UUID tenantId = getRequiredTenantId();
        templateService.softDeleteTemplate(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Create a new draft version under a template.
     */
    @PostMapping("/{id}/versions")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<TemplateVersionResponse> createDraftVersion(@PathVariable UUID id,
                                                                      @Valid @RequestBody CreateVersionRequest request) {
        UUID tenantId = getRequiredTenantId();
        TemplateVersionResponse response = templateService.createDraftVersion(tenantId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all versions for a template.
     */
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:read')")
    public ResponseEntity<List<TemplateVersionResponse>> listVersions(@PathVariable UUID id) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.listVersions(tenantId, id));
    }

    /**
     * Get a specific template version by templateId and versionNumber.
     * Readable even if parent template was soft-deleted.
     */
    @GetMapping("/{id}/versions/{versionNumber}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:read')")
    public ResponseEntity<TemplateVersionResponse> getVersion(@PathVariable UUID id,
                                                              @PathVariable Integer versionNumber) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.getVersion(tenantId, id, versionNumber));
    }

    /**
     * Update a draft version by versionNumber.
     */
    @PutMapping("/{id}/versions/{versionNumber}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<TemplateVersionResponse> updateDraftVersion(@PathVariable UUID id,
                                                                      @PathVariable Integer versionNumber,
                                                                      @Valid @RequestBody UpdateVersionRequest request) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.updateDraftVersion(tenantId, id, versionNumber, request));
    }

    /**
     * Atomically publish a draft version by versionNumber.
     */
    @PostMapping("/{id}/versions/{versionNumber}/publish")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<TemplateVersionResponse> publishVersion(@PathVariable UUID id,
                                                                  @PathVariable Integer versionNumber) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.publishVersion(tenantId, id, versionNumber));
    }

    /**
     * Preview / render a template version by versionNumber with input variables.
     */
    @PostMapping("/{id}/versions/{versionNumber}/preview")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:read')")
    public ResponseEntity<RenderedTemplateResponse> previewVersion(@PathVariable UUID id,
                                                                   @PathVariable Integer versionNumber,
                                                                   @RequestBody(required = false) PreviewTemplateRequest request) {
        UUID tenantId = getRequiredTenantId();
        var vars = (request != null && request.variables() != null) ? request.variables() : java.util.Collections.<String, Object>emptyMap();
        return ResponseEntity.ok(templateService.previewVersion(tenantId, id, versionNumber, vars));
    }

    /**
     * Direct read of a template version by versionId for notification history reference.
     */
    @GetMapping("/versions/{versionId}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:read')")
    public ResponseEntity<TemplateVersionResponse> getVersionDirect(@PathVariable UUID versionId) {
        UUID tenantId = getRequiredTenantId();
        return ResponseEntity.ok(templateService.getVersionDirect(tenantId, versionId));
    }

    /**
     * Delete a specific template version by versionNumber.
     */
    @DeleteMapping("/{id}/versions/{versionNumber}")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<Void> deleteVersion(@PathVariable UUID id,
                                              @PathVariable Integer versionNumber) {
        UUID tenantId = getRequiredTenantId();
        templateService.deleteVersion(tenantId, id, versionNumber);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk delete multiple template versions at a time (POST).
     */
    @PostMapping("/{id}/versions/delete")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<DeleteVersionsResponse> deleteVersionsPost(@PathVariable UUID id,
                                                                     @Valid @RequestBody DeleteVersionsRequest request) {
        UUID tenantId = getRequiredTenantId();
        DeleteVersionsResponse response = templateService.deleteVersions(tenantId, id, request.versionNumbers());
        return ResponseEntity.ok(response);
    }

    /**
     * Bulk delete multiple template versions at a time (DELETE).
     */
    @DeleteMapping("/{id}/versions")
    @PreAuthorize("hasRole('TENANT_OWNER') or hasAuthority('SCOPE_templates:write')")
    public ResponseEntity<DeleteVersionsResponse> deleteVersionsDelete(@PathVariable UUID id,
                                                                       @Valid @RequestBody DeleteVersionsRequest request) {
        UUID tenantId = getRequiredTenantId();
        DeleteVersionsResponse response = templateService.deleteVersions(tenantId, id, request.versionNumbers());
        return ResponseEntity.ok(response);
    }

    private UUID getRequiredTenantId() {
        return TenantContext.get()
                .map(TenantPrincipal::tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.UNAUTHENTICATED, "No authenticated tenant context"));
    }
}
