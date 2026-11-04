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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TemplateServiceTest {

    private TemplateRepository templateRepository;
    private TemplateVersionRepository templateVersionRepository;
    private TenantRepository tenantRepository;
    private TemplateRenderingEngine renderingEngine;
    private Clock clock;
    private TemplateService templateService;

    private final UUID tenantId = UUID.randomUUID();
    private TenantEntity activeTenant;

    @BeforeEach
    void setUp() {
        templateRepository = mock(TemplateRepository.class);
        templateVersionRepository = mock(TemplateVersionRepository.class);
        tenantRepository = mock(TenantRepository.class);
        renderingEngine = new TemplateRenderingEngine();
        clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);

        templateService = new TemplateService(
                templateRepository,
                templateVersionRepository,
                tenantRepository,
                renderingEngine,
                clock
        );

        activeTenant = new TenantEntity("Acme Corp", "acme");
        activeTenant.setId(tenantId);
        activeTenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(activeTenant));
    }

    @Test
    @DisplayName("Create template succeeds and saves initial version 1 (DRAFT)")
    void createTemplateSuccess() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "Welcome",
                Channel.EMAIL,
                "Welcome description",
                "Welcome {{name}}",
                "Hello {{name}}, welcome to Notify!",
                ContentType.TEXT
        );

        when(templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, "Welcome")).thenReturn(false);
        when(templateRepository.save(any(TemplateEntity.class))).thenAnswer(invocation -> {
            TemplateEntity t = invocation.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(templateVersionRepository.save(any(TemplateVersionEntity.class))).thenAnswer(invocation -> {
            TemplateVersionEntity v = invocation.getArgument(0);
            v.setId(UUID.randomUUID());
            return v;
        });

        TemplateResponse response = templateService.createTemplate(tenantId, request);

        assertThat(response.name()).isEqualTo("Welcome");
        assertThat(response.channel()).isEqualTo(Channel.EMAIL);
        assertThat(response.currentVersion()).isNotNull();
        assertThat(response.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(response.currentVersion().status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(response.currentVersion().variables()).containsExactly("name");
    }

    @Test
    @DisplayName("Create template rejects duplicate active name with VALIDATION_FAILED (422)")
    void createTemplateDuplicateName() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "Welcome",
                Channel.EMAIL,
                null,
                "Sub",
                "Body",
                ContentType.TEXT
        );

        when(templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, "Welcome")).thenReturn(true);

        assertThatThrownBy(() -> templateService.createTemplate(tenantId, request))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("already exists");
                });
    }

    @Test
    @DisplayName("Create template EMAIL channel requires non-blank subject")
    void createTemplateEmailRequiresSubject() {
        CreateTemplateRequest request = new CreateTemplateRequest(
                "Welcome",
                Channel.EMAIL,
                null,
                "",
                "Body",
                ContentType.TEXT
        );

        when(templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, "Welcome")).thenReturn(false);

        assertThatThrownBy(() -> templateService.createTemplate(tenantId, request))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("Subject is required");
                });
    }

    @Test
    @DisplayName("Update draft version rejects modification of PUBLISHED version with TEMPLATE_VERSION_IMMUTABLE (409)")
    void updateDraftVersionRejectsPublished() {
        UUID templateId = UUID.randomUUID();

        TemplateEntity template = new TemplateEntity(tenantId, "Welcome", Channel.EMAIL, null);
        template.setId(templateId);

        TemplateVersionEntity version = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED, "Sub", "Body", ContentType.TEXT, List.of()
        );

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 1)).thenReturn(Optional.of(version));

        UpdateVersionRequest request = new UpdateVersionRequest("New Sub", "New Body", ContentType.TEXT);

        assertThatThrownBy(() -> templateService.updateDraftVersion(tenantId, templateId, 1, request))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_VERSION_IMMUTABLE);
                });
    }

    @Test
    @DisplayName("Publish version locks parent, archives previous published version, and flushes before publishing new version")
    void publishVersionFlushesArchiveBeforePublish() {
        UUID templateId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        UUID v2Id = UUID.randomUUID();

        TemplateEntity template = new TemplateEntity(tenantId, "Welcome", Channel.EMAIL, null);
        template.setId(templateId);

        TemplateVersionEntity v1 = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED, "Sub 1", "Body 1", ContentType.TEXT, List.of()
        );
        v1.setId(v1Id);

        TemplateVersionEntity v2 = new TemplateVersionEntity(
                tenantId, templateId, 2, TemplateVersionStatus.DRAFT, "Sub 2", "Body 2", ContentType.TEXT, List.of()
        );
        v2.setId(v2Id);

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 2)).thenReturn(Optional.of(v2));
        when(templateVersionRepository.findByTemplateIdAndStatus(templateId, TemplateVersionStatus.PUBLISHED)).thenReturn(Optional.of(v1));
        when(templateVersionRepository.saveAndFlush(any(TemplateVersionEntity.class))).thenAnswer(i -> i.getArgument(0));

        TemplateVersionResponse result = templateService.publishVersion(tenantId, templateId, 2);

        assertThat(result.status()).isEqualTo(TemplateVersionStatus.PUBLISHED);
        assertThat(v1.getStatus()).isEqualTo(TemplateVersionStatus.ARCHIVED);

        // Verify order: v1 is archived and flushed FIRST, then v2 is published and flushed
        InOrder inOrder = inOrder(templateRepository, templateVersionRepository);
        inOrder.verify(templateRepository).findByIdAndTenantIdForUpdate(templateId, tenantId);
        inOrder.verify(templateVersionRepository).saveAndFlush(v1);
        inOrder.verify(templateVersionRepository).saveAndFlush(v2);
    }

    @Test
    @DisplayName("Soft-deleted template hides from getTemplate (404) but version remains readable for history")
    void softDeletePreservesVersionReadability() {
        UUID templateId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        TemplateEntity template = new TemplateEntity(tenantId, "Welcome", Channel.EMAIL, null);
        template.setId(templateId);
        template.setDeletedAt(Instant.now(clock));

        TemplateVersionEntity version = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED, "Sub", "Body", ContentType.TEXT, List.of()
        );
        version.setId(versionId);

        when(templateRepository.findByIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId)).thenReturn(Optional.empty());
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 1)).thenReturn(Optional.of(version));
        when(templateVersionRepository.findByTenantIdAndId(tenantId, versionId)).thenReturn(Optional.of(version));

        // Normal template get returns 404
        assertThatThrownBy(() -> templateService.getTemplate(tenantId, templateId))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        // But getVersion and getVersionDirect succeed for historical reference
        TemplateVersionResponse vResp = templateService.getVersion(tenantId, templateId, 1);
        assertThat(vResp).isNotNull();
        assertThat(vResp.versionNumber()).isEqualTo(1);

        TemplateVersionResponse directResp = templateService.getVersionDirect(tenantId, versionId);
        assertThat(directResp).isNotNull();
        assertThat(directResp.id()).isEqualTo(versionId);
    }

    @Test
    @DisplayName("Suspended tenant allows reads but blocks writes with TENANT_SUSPENDED (403)")
    void suspendedTenantEnforcement() {
        activeTenant.setStatus(TenantStatus.SUSPENDED);

        CreateTemplateRequest request = new CreateTemplateRequest(
                "Welcome", Channel.EMAIL, null, "Sub", "Body", ContentType.TEXT
        );

        // Write blocked
        assertThatThrownBy(() -> templateService.createTemplate(tenantId, request))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.TENANT_SUSPENDED));

        // Read allowed
        when(templateRepository.findByTenantIdAndDeletedAtIsNull(eq(tenantId), any())).thenReturn(List.of());
        List<TemplateResponse> templates = templateService.listTemplates(tenantId);
        assertThat(templates).isEmpty();
    }

    @Test
    @DisplayName("Create draft version clones from source version under same template")
    void createDraftVersionCloneSuccess() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Order Placed", Channel.EMAIL, null);
        template.setId(templateId);

        TemplateVersionEntity sourceVersion = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED,
                "Order {{orderId}} Confirmed", "Your order {{orderId}} is on the way to {{customer}}",
                ContentType.TEXT, List.of("orderId", "customer")
        );

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 1))
                .thenReturn(Optional.of(sourceVersion));
        when(templateVersionRepository.findMaxVersionNumberByTemplateId(templateId)).thenReturn(Optional.of(1));
        when(templateVersionRepository.save(any(TemplateVersionEntity.class))).thenAnswer(i -> i.getArgument(0));

        // Clone without overrides
        CreateVersionRequest cloneReq = new CreateVersionRequest(1, null, null, null);
        TemplateVersionResponse cloned = templateService.createDraftVersion(tenantId, templateId, cloneReq);

        assertThat(cloned.versionNumber()).isEqualTo(2);
        assertThat(cloned.status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(cloned.subject()).isEqualTo("Order {{orderId}} Confirmed");
        assertThat(cloned.body()).isEqualTo("Your order {{orderId}} is on the way to {{customer}}");
        assertThat(cloned.contentType()).isEqualTo(ContentType.TEXT);
        assertThat(cloned.variables()).containsExactlyInAnyOrder("orderId", "customer");
    }

    @Test
    @DisplayName("Create draft version clones with subject override")
    void createDraftVersionCloneWithOverride() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Order Placed", Channel.EMAIL, null);
        template.setId(templateId);

        TemplateVersionEntity sourceVersion = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED,
                "Old Subject", "Body with {{var}}", ContentType.TEXT, List.of("var")
        );

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 1))
                .thenReturn(Optional.of(sourceVersion));
        when(templateVersionRepository.findMaxVersionNumberByTemplateId(templateId)).thenReturn(Optional.of(1));
        when(templateVersionRepository.save(any(TemplateVersionEntity.class))).thenAnswer(i -> i.getArgument(0));

        CreateVersionRequest cloneReq = new CreateVersionRequest(1, "New Subject {{var}}", null, null);
        TemplateVersionResponse cloned = templateService.createDraftVersion(tenantId, templateId, cloneReq);

        assertThat(cloned.versionNumber()).isEqualTo(2);
        assertThat(cloned.subject()).isEqualTo("New Subject {{var}}");
        assertThat(cloned.body()).isEqualTo("Body with {{var}}");
    }

    @Test
    @DisplayName("Create draft version clones with body override")
    void createDraftVersionCloneWithBodyOverride() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Order Placed", Channel.EMAIL, null);
        template.setId(templateId);

        TemplateVersionEntity sourceVersion = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED,
                "Old Subject {{orderId}}", "Old body with {{oldVar}}", ContentType.TEXT, List.of("oldVar", "orderId")
        );

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 1))
                .thenReturn(Optional.of(sourceVersion));
        when(templateVersionRepository.findMaxVersionNumberByTemplateId(templateId)).thenReturn(Optional.of(1));
        when(templateVersionRepository.save(any(TemplateVersionEntity.class))).thenAnswer(i -> i.getArgument(0));

        CreateVersionRequest cloneReq = new CreateVersionRequest(1, null, "Brand new body with {{newVar}}", null);
        TemplateVersionResponse cloned = templateService.createDraftVersion(tenantId, templateId, cloneReq);

        assertThat(cloned.versionNumber()).isEqualTo(2);
        assertThat(cloned.subject()).isEqualTo("Old Subject {{orderId}}");
        assertThat(cloned.body()).isEqualTo("Brand new body with {{newVar}}");
        assertThat(cloned.variables()).containsExactlyInAnyOrder("newVar", "orderId");
    }

    @Test
    @DisplayName("Create draft version clones with both subject and body override")
    void createDraftVersionCloneWithBothSubjectAndBodyOverride() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Order Placed", Channel.EMAIL, null);
        template.setId(templateId);

        TemplateVersionEntity sourceVersion = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.PUBLISHED,
                "Old Subject", "Old body", ContentType.TEXT, List.of()
        );

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 1))
                .thenReturn(Optional.of(sourceVersion));
        when(templateVersionRepository.findMaxVersionNumberByTemplateId(templateId)).thenReturn(Optional.of(1));
        when(templateVersionRepository.save(any(TemplateVersionEntity.class))).thenAnswer(i -> i.getArgument(0));

        CreateVersionRequest cloneReq = new CreateVersionRequest(1, "Updated Subject {{subVar}}", "Updated Body {{bodyVar}}", ContentType.HTML);
        TemplateVersionResponse cloned = templateService.createDraftVersion(tenantId, templateId, cloneReq);

        assertThat(cloned.versionNumber()).isEqualTo(2);
        assertThat(cloned.subject()).isEqualTo("Updated Subject {{subVar}}");
        assertThat(cloned.body()).isEqualTo("Updated Body {{bodyVar}}");
        assertThat(cloned.contentType()).isEqualTo(ContentType.HTML);
        assertThat(cloned.variables()).containsExactlyInAnyOrder("subVar", "bodyVar");
    }

    @Test
    @DisplayName("Create draft version clone rejects non-existent source version with NOT_FOUND (404)")
    void createDraftVersionCloneNotFound() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Order Placed", Channel.EMAIL, null);
        template.setId(templateId);

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdAndVersionNumber(tenantId, templateId, 99))
                .thenReturn(Optional.empty());

        CreateVersionRequest cloneReq = new CreateVersionRequest(99, null, null, null);

        assertThatThrownBy(() -> templateService.createDraftVersion(tenantId, templateId, cloneReq))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("Create draft version without clone requires non-blank body")
    void createDraftVersionRequiresBodyWhenNotCloning() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Order Placed", Channel.WEBHOOK, null);
        template.setId(templateId);

        when(templateRepository.findByIdAndTenantIdForUpdate(templateId, tenantId)).thenReturn(Optional.of(template));

        CreateVersionRequest req = new CreateVersionRequest(null, null, "", ContentType.TEXT);

        assertThatThrownBy(() -> templateService.createDraftVersion(tenantId, templateId, req))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("List versions returns versions ordered descending and rejects cross-tenant template")
    void listVersionsSuccessAndIsolation() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity(tenantId, "Templ", Channel.IN_APP, null);
        template.setId(templateId);

        TemplateVersionEntity v1 = new TemplateVersionEntity(
                tenantId, templateId, 1, TemplateVersionStatus.ARCHIVED, null, "v1", ContentType.TEXT, List.of()
        );
        TemplateVersionEntity v2 = new TemplateVersionEntity(
                tenantId, templateId, 2, TemplateVersionStatus.PUBLISHED, null, "v2", ContentType.TEXT, List.of()
        );

        when(templateRepository.findByIdAndTenantId(templateId, tenantId)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findByTenantIdAndTemplateIdOrderByVersionNumberDesc(tenantId, templateId))
                .thenReturn(List.of(v2, v1));

        List<TemplateVersionResponse> versions = templateService.listVersions(tenantId, templateId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNumber()).isEqualTo(2);
        assertThat(versions.get(1).versionNumber()).isEqualTo(1);

        // Unknown template or cross-tenant returns 404
        UUID otherId = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantId(otherId, tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> templateService.listVersions(tenantId, otherId))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> assertThat(((NotifyException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
