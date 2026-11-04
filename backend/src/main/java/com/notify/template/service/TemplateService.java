package com.notify.template.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Channel;
import com.notify.common.model.ContentType;
import com.notify.common.model.TemplateVersionStatus;
import com.notify.common.model.TenantStatus;
import com.notify.template.dto.*;
import com.notify.template.engine.TemplateRenderingEngine;
import com.notify.template.entity.TemplateEntity;
import com.notify.template.entity.TemplateVersionEntity;
import com.notify.template.repository.TemplateRepository;
import com.notify.template.repository.TemplateVersionRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service managing tenant-scoped template lifecycle, immutable published versions,
 * parent-template serialized publication, and safe rendering.
 */
@Service
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TenantRepository tenantRepository;
    private final TemplateRenderingEngine renderingEngine;
    private final Clock clock;

    public TemplateService(TemplateRepository templateRepository,
                           TemplateVersionRepository templateVersionRepository,
                           TenantRepository tenantRepository,
                           TemplateRenderingEngine renderingEngine,
                           Clock clock) {
        this.templateRepository = templateRepository;
        this.templateVersionRepository = templateVersionRepository;
        this.tenantRepository = tenantRepository;
        this.renderingEngine = renderingEngine;
        this.clock = clock;
    }

    /**
     * Create a new template and its initial Version 1 (DRAFT).
     */
    @Transactional
    public TemplateResponse createTemplate(UUID tenantId, CreateTemplateRequest request) {
        checkTenantAccess(tenantId, true);

        String trimmedName = request.name().trim();
        if (templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, trimmedName)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "A template with name '" + trimmedName + "' already exists");
        }

        validateChannelAndContent(request.channel(), request.subject(), request.body());

        // Validate template syntax
        renderingEngine.validateTemplateSyntax(request.body());
        if (request.subject() != null) {
            renderingEngine.validateTemplateSyntax(request.subject());
        }

        List<String> variables = renderingEngine.extractVariables(request.body(), request.subject());
        ContentType contentType = request.contentType() != null
                ? request.contentType()
                : ContentType.TEXT;

        Instant now = Instant.now(clock);
        TemplateEntity template = new TemplateEntity(tenantId, trimmedName, request.channel(), request.description());
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        TemplateVersionEntity version = new TemplateVersionEntity(
                tenantId,
                template.getId(),
                1,
                TemplateVersionStatus.DRAFT,
                request.subject(),
                request.body(),
                contentType,
                variables
        );
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = templateVersionRepository.save(version);

        return toTemplateResponse(template, version);
    }

    /**
     * Lists all active templates for a tenant (excluding soft-deleted templates).
     */
    @Transactional(readOnly = true)
    public List<TemplateResponse> listTemplates(UUID tenantId) {
        checkTenantAccess(tenantId, false);

        List<TemplateEntity> templates = templateRepository.findByTenantIdAndDeletedAtIsNull(
                tenantId,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return templates.stream()
                .map(t -> {
                    TemplateVersionEntity published = templateVersionRepository
                            .findByTemplateIdAndStatus(t.getId(), TemplateVersionStatus.PUBLISHED)
                            .orElse(null);
                    return toTemplateResponse(t, published);
                })
                .collect(Collectors.toList());
    }

    /**
     * Retrieves template details with all its versions.
     * Returns 404 if soft-deleted or cross-tenant.
     */
    @Transactional(readOnly = true)
    public TemplateDetailResponse getTemplate(UUID tenantId, UUID templateId) {
        checkTenantAccess(tenantId, false);

        TemplateEntity template = templateRepository.findByIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        List<TemplateVersionEntity> versions = templateVersionRepository
                .findByTenantIdAndTemplateIdOrderByVersionNumberDesc(tenantId, templateId);

        List<TemplateVersionResponse> versionResponses = versions.stream()
                .map(this::toVersionResponse)
                .collect(Collectors.toList());

        return new TemplateDetailResponse(
                template.getId(),
                template.getName(),
                template.getChannel(),
                template.getDescription(),
                template.getDeletedAt(),
                template.getCreatedAt(),
                template.getUpdatedAt(),
                versionResponses
        );
    }

    /**
     * Updates an active template's name and/or description.
     */
    @Transactional
    public TemplateResponse updateTemplate(UUID tenantId, UUID templateId, UpdateTemplateRequest request) {
        checkTenantAccess(tenantId, true);

        TemplateEntity template = templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        if (template.getDeletedAt() != null) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId);
        }

        if (request.name() != null && !request.name().isBlank()) {
            String trimmedName = request.name().trim();
            if (templateRepository.existsByTenantIdAndNameIgnoreCaseAndIdNotAndDeletedAtIsNull(tenantId, trimmedName, templateId)) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "A template with name '" + trimmedName + "' already exists");
            }
            template.setName(trimmedName);
        }

        if (request.description() != null) {
            template.setDescription(request.description());
        }

        template.setUpdatedAt(Instant.now(clock));
        template = templateRepository.save(template);

        TemplateVersionEntity published = templateVersionRepository
                .findByTemplateIdAndStatus(templateId, TemplateVersionStatus.PUBLISHED)
                .orElse(null);

        return toTemplateResponse(template, published);
    }

    /**
     * Soft-deletes a template. Hides from lists, but existing versions remain readable for history per CONTRACTS.md.
     */
    @Transactional
    public void softDeleteTemplate(UUID tenantId, UUID templateId) {
        checkTenantAccess(tenantId, true);

        TemplateEntity template = templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        if (template.getDeletedAt() != null) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId);
        }

        Instant now = Instant.now(clock);
        template.setDeletedAt(now);
        template.setUpdatedAt(now);
        templateRepository.save(template);
    }

    /**
     * Creates a new draft version under a parent template.
     * Optionally clones subject, body, and contentType from an existing version of the same template.
     * Serialized via parent-template pessimistic lock.
     */
    @Transactional
    public TemplateVersionResponse createDraftVersion(UUID tenantId, UUID templateId, CreateVersionRequest request) {
        checkTenantAccess(tenantId, true);

        TemplateEntity template = templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        if (template.getDeletedAt() != null) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId);
        }

        String subject;
        String body;
        ContentType contentType;

        if (request.cloneFromVersion() != null) {
            if (request.cloneFromVersion() < 1) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "cloneFromVersion must be a positive integer");
            }
            TemplateVersionEntity sourceVersion = templateVersionRepository
                    .findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, request.cloneFromVersion())
                    .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND,
                            "Source template version not found: " + request.cloneFromVersion()));

            String reqSubject = (request.subject() != null && !request.subject().isBlank())
                    ? request.subject().trim()
                    : null;
            subject = reqSubject != null ? reqSubject : sourceVersion.getSubject();

            String reqBody = (request.body() != null && !request.body().isBlank())
                    ? request.body().trim()
                    : null;
            body = reqBody != null ? reqBody : sourceVersion.getBody();

            contentType = request.contentType() != null ? request.contentType() : sourceVersion.getContentType();
        } else {
            if (request.body() == null || request.body().isBlank()) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Template body cannot be blank");
            }
            subject = (request.subject() != null && !request.subject().isBlank()) ? request.subject().trim() : null;
            body = request.body().trim();
            contentType = request.contentType() != null ? request.contentType() : ContentType.TEXT;
        }

        validateChannelAndContent(template.getChannel(), subject, body);

        renderingEngine.validateTemplateSyntax(body);
        if (subject != null) {
            renderingEngine.validateTemplateSyntax(subject);
        }

        List<String> variables = renderingEngine.extractVariables(body, subject);

        int nextVersion = templateVersionRepository.findMaxVersionNumberByTemplateId(templateId).orElse(0) + 1;
        Instant now = Instant.now(clock);

        TemplateVersionEntity version = new TemplateVersionEntity(
                tenantId,
                templateId,
                nextVersion,
                TemplateVersionStatus.DRAFT,
                subject,
                body,
                contentType,
                variables
        );
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = templateVersionRepository.save(version);

        template.setUpdatedAt(now);
        templateRepository.save(template);

        return toVersionResponse(version);
    }

    /**
     * Lists all versions for a template ordered by version number descending.
     * Allowed even if parent template was soft-deleted for historical audit trails.
     */
    @Transactional(readOnly = true)
    public List<TemplateVersionResponse> listVersions(UUID tenantId, UUID templateId) {
        checkTenantAccess(tenantId, false);

        templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        List<TemplateVersionEntity> versions = templateVersionRepository
                .findByTenantIdAndTemplateIdOrderByVersionNumberDesc(tenantId, templateId);

        return versions.stream()
                .map(this::toVersionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Updates an existing DRAFT version by version number. Rejects edits to PUBLISHED or ARCHIVED versions with HTTP 409.
     */
    @Transactional
    public TemplateVersionResponse updateDraftVersion(UUID tenantId, UUID templateId, Integer versionNumber, UpdateVersionRequest request) {
        checkTenantAccess(tenantId, true);

        TemplateEntity template = templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        if (template.getDeletedAt() != null) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId);
        }

        TemplateVersionEntity version = templateVersionRepository
                .findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, versionNumber)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template version not found: " + versionNumber));

        if (version.getStatus() != TemplateVersionStatus.DRAFT) {
            throw new NotifyException(ErrorCode.TEMPLATE_VERSION_IMMUTABLE,
                    "Published and archived template versions are immutable and cannot be modified");
        }

        String trimmedSubject = (request.subject() != null && !request.subject().isBlank())
                ? request.subject().trim()
                : null;
        String trimmedBody = request.body().trim();

        validateChannelAndContent(template.getChannel(), trimmedSubject, trimmedBody);

        renderingEngine.validateTemplateSyntax(trimmedBody);
        if (trimmedSubject != null) {
            renderingEngine.validateTemplateSyntax(trimmedSubject);
        }

        List<String> variables = renderingEngine.extractVariables(trimmedBody, trimmedSubject);
        ContentType contentType = request.contentType() != null
                ? request.contentType()
                : version.getContentType();

        version.setSubject(trimmedSubject);
        version.setBody(trimmedBody);
        version.setContentType(contentType);
        version.setVariables(variables);
        Instant now = Instant.now(clock);
        version.setUpdatedAt(now);
        version = templateVersionRepository.save(version);

        template.setUpdatedAt(now);
        templateRepository.save(template);

        return toVersionResponse(version);
    }

    /**
     * Atomically publishes a draft version by version number.
     * Serialized with a pessimistic write lock on the parent template.
     * Archives and flushes any existing published version first so the partial unique index
     * (uq_template_one_published) is respected.
     */
    @Transactional
    public TemplateVersionResponse publishVersion(UUID tenantId, UUID templateId, Integer versionNumber) {
        checkTenantAccess(tenantId, true);

        TemplateEntity template = templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        if (template.getDeletedAt() != null) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId);
        }

        TemplateVersionEntity targetVersion = templateVersionRepository
                .findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, versionNumber)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template version not found: " + versionNumber));

        if (targetVersion.getStatus() == TemplateVersionStatus.PUBLISHED) {
            return toVersionResponse(targetVersion);
        }

        if (targetVersion.getStatus() == TemplateVersionStatus.ARCHIVED) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot publish an archived template version");
        }

        Instant now = Instant.now(clock);

        // 1. Archive and flush previous published row if present
        Optional<TemplateVersionEntity> currentPublished = templateVersionRepository
                .findByTemplateIdAndStatus(templateId, TemplateVersionStatus.PUBLISHED);

        if (currentPublished.isPresent()) {
            TemplateVersionEntity previous = currentPublished.get();
            previous.setStatus(TemplateVersionStatus.ARCHIVED);
            previous.setUpdatedAt(now);
            templateVersionRepository.saveAndFlush(previous);
        }

        // 2. Publish target version and flush
        targetVersion.setStatus(TemplateVersionStatus.PUBLISHED);
        targetVersion.setUpdatedAt(now);
        targetVersion = templateVersionRepository.saveAndFlush(targetVersion);

        template.setUpdatedAt(now);
        templateRepository.save(template);

        return toVersionResponse(targetVersion);
    }

    /**
     * Reads a specific template version by templateId and versionNumber.
     * Allowed even if parent template is soft-deleted (for historical notifications),
     * while preserving strict tenant isolation.
     */
    @Transactional(readOnly = true)
    public TemplateVersionResponse getVersion(UUID tenantId, UUID templateId, Integer versionNumber) {
        checkTenantAccess(tenantId, false);

        TemplateVersionEntity version = templateVersionRepository
                .findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, versionNumber)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template version not found: " + versionNumber));

        return toVersionResponse(version);
    }

    /**
     * Direct read of a template version by version ID across tenant templates.
     * Preserves strict tenant isolation and historical accessibility.
     */
    @Transactional(readOnly = true)
    public TemplateVersionResponse getVersionDirect(UUID tenantId, UUID versionId) {
        checkTenantAccess(tenantId, false);

        TemplateVersionEntity version = templateVersionRepository
                .findByTenantIdAndId(tenantId, versionId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template version not found: " + versionId));

        return toVersionResponse(version);
    }

    /**
     * Renders a preview of a template version by version number with the provided variables.
     * Missing variables result in HTTP 422 TEMPLATE_VARIABLE_MISSING.
     */
    @Transactional(readOnly = true)
    public RenderedTemplateResponse previewVersion(UUID tenantId, UUID templateId, Integer versionNumber, Map<String, Object> variables) {
        checkTenantAccess(tenantId, false);

        TemplateVersionEntity version = templateVersionRepository
                .findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, versionNumber)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template version not found: " + versionNumber));

        String renderedSubject = null;
        if (version.getSubject() != null) {
            renderedSubject = renderingEngine.render(version.getSubject(), variables, ContentType.TEXT);
        }

        String renderedBody = renderingEngine.render(version.getBody(), variables, version.getContentType());

        return new RenderedTemplateResponse(renderedSubject, renderedBody, version.getContentType());
    }

    /**
     * Resolves a template and renders a snapshot for notification creation.
     */
    @Transactional(readOnly = true)
    public ResolvedTemplateSnapshot resolveAndRender(
            UUID tenantId,
            UUID templateId,
            Integer versionNumber,
            Map<String, Object> variables
    ) {
        checkTenantAccess(tenantId, false);

        TemplateEntity template = templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId));

        if (template.getDeletedAt() != null) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Template not found: " + templateId);
        }

        TemplateVersionEntity version;
        if (versionNumber != null) {
            version = templateVersionRepository
                    .findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, versionNumber)
                    .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Template version not found: " + versionNumber));
            if (version.getStatus() == TemplateVersionStatus.DRAFT) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Cannot send notification using a DRAFT template version");
            }
        } else {
            version = templateVersionRepository
                    .findByTemplateIdAndStatus(templateId, TemplateVersionStatus.PUBLISHED)
                    .orElseThrow(() -> new NotifyException(ErrorCode.VALIDATION_FAILED, "Template has no published version"));
        }

        Map<String, Object> vars = variables != null ? variables : Collections.emptyMap();
        String renderedSubject = null;
        if (version.getSubject() != null) {
            renderedSubject = renderingEngine.render(version.getSubject(), vars, ContentType.TEXT);
        }

        String renderedBody = renderingEngine.render(version.getBody(), vars, version.getContentType());

        return new ResolvedTemplateSnapshot(version.getId(), renderedSubject, renderedBody, version.getContentType());
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private void checkTenantAccess(UUID tenantId, boolean isWrite) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found: " + tenantId));

        if (tenant.getStatus() == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant account has been revoked. Contact support.");
        }

        if (isWrite && tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new NotifyException(ErrorCode.TENANT_SUSPENDED, "Tenant account is suspended");
        }
    }

    private void validateChannelAndContent(Channel channel, String subject, String body) {
        if (body == null || body.isBlank()) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Template body cannot be blank");
        }

        if (channel == Channel.EMAIL) {
            if (subject == null || subject.isBlank()) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Subject is required for EMAIL templates");
            }
        }
    }

    public TemplateResponse toTemplateResponse(TemplateEntity template, TemplateVersionEntity currentVersion) {
        TemplateVersionResponse versionResp = currentVersion != null ? toVersionResponse(currentVersion) : null;
        return new TemplateResponse(
                template.getId(),
                template.getName(),
                template.getChannel(),
                template.getDescription(),
                template.getDeletedAt(),
                template.getCreatedAt(),
                template.getUpdatedAt(),
                versionResp
        );
    }

    public TemplateVersionResponse toVersionResponse(TemplateVersionEntity version) {
        return new TemplateVersionResponse(
                version.getId(),
                version.getTemplateId(),
                version.getVersionNumber(),
                version.getStatus(),
                version.getSubject(),
                version.getBody(),
                version.getContentType(),
                version.getVariables(),
                version.getCreatedAt(),
                version.getUpdatedAt()
        );
    }
}
