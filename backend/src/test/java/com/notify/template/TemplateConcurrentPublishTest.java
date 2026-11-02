package com.notify.template;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.ContentType;
import com.notify.common.model.TemplateVersionStatus;
import com.notify.common.model.TenantStatus;
import com.notify.template.dto.CreateTemplateRequest;
import com.notify.template.dto.CreateVersionRequest;
import com.notify.template.dto.TemplateResponse;
import com.notify.template.dto.TemplateVersionResponse;
import com.notify.template.entity.TemplateVersionEntity;
import com.notify.template.repository.TemplateVersionRepository;
import com.notify.template.service.TemplateService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateConcurrentPublishTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TemplateService templateService;

    @Autowired
    private TemplateVersionRepository versionRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Concurrent publication serialize via parent-template lock and leaves exactly one PUBLISHED version")
    void concurrentPublishSerializedWithoutConstraintViolation() throws Exception {
        // 1. Create a tenant
        TenantEntity tenant = new TenantEntity("ConcurrentTenant", "concurrent-pub");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant = tenantRepository.saveAndFlush(tenant);
        UUID tenantId = tenant.getId();

        // 2. Create a template (initial version 1 is DRAFT)
        CreateTemplateRequest req = new CreateTemplateRequest(
                "Concurrent Template",
                Channel.EMAIL,
                "Concurrent testing",
                "Subject {{v}}",
                "Body version {{v}}",
                ContentType.TEXT
        );
        TemplateResponse template = templateService.createTemplate(tenantId, req);
        UUID templateId = template.id();
        UUID v1Id = template.currentVersion().id();

        // 3. Create 4 more draft versions (v2, v3, v4, v5)
        List<Integer> versionNumbers = new ArrayList<>();
        versionNumbers.add(1);

        for (int i = 2; i <= 5; i++) {
            CreateVersionRequest vReq = new CreateVersionRequest(
                    "Subject v" + i,
                    "Body v" + i,
                    ContentType.TEXT
            );
            TemplateVersionResponse vResp = templateService.createDraftVersion(tenantId, templateId, vReq);
            versionNumbers.add(vResp.versionNumber());
        }

        assertThat(versionNumbers).hasSize(5);

        // 4. Concurrently publish all 5 versions across 5 threads simultaneously
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<TemplateVersionResponse>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (Integer vNum : versionNumbers) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // wait for simultaneous trigger
                    TemplateVersionResponse res = templateService.publishVersion(tenantId, templateId, vNum);
                    successCount.incrementAndGet();
                    return res;
                } catch (Throwable t) {
                    errors.add(t);
                    throw new RuntimeException(t);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(5);

        // 5. Verify PostgreSQL database state via raw JDBC
        try (Connection conn = dataSource.getConnection()) {
            // Assert: Exactly ONE published row exists for this template
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT count(*) FROM template_versions WHERE template_id = ? AND status = 'PUBLISHED'")) {
                stmt.setObject(1, templateId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    long publishedCount = rs.getLong(1);
                    assertThat(publishedCount)
                            .as("There must be exactly one PUBLISHED version in PostgreSQL")
                            .isEqualTo(1);
                }
            }

            // Assert: Exactly FOUR archived rows exist
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT count(*) FROM template_versions WHERE template_id = ? AND status = 'ARCHIVED'")) {
                stmt.setObject(1, templateId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    long archivedCount = rs.getLong(1);
                    assertThat(archivedCount)
                            .as("All previously published versions must be ARCHIVED")
                            .isEqualTo(4);
                }
            }

            // Assert: Zero draft rows remain
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT count(*) FROM template_versions WHERE template_id = ? AND status = 'DRAFT'")) {
                stmt.setObject(1, templateId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    long draftCount = rs.getLong(1);
                    assertThat(draftCount).isEqualTo(0);
                }
            }
        }
    }
}
