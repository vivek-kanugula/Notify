package com.notify.tenant.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** T1 (service half): authoritative tenant status read used by the worker tenant gate. */
class TenantServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID tenantWithStatus(TenantStatus status) {
        TenantEntity tenant = new TenantEntity("StatusOrg", "status-org-" + UUID.randomUUID());
        tenant.setStatus(status);
        return tenantRepository.save(tenant).getId();
    }

    @Test
    @DisplayName("T1 returns the stored status for ACTIVE, SUSPENDED and REVOKED tenants")
    void returnsStoredStatus() {
        assertThat(tenantService.getTenantStatus(tenantWithStatus(TenantStatus.ACTIVE))).contains(TenantStatus.ACTIVE);
        assertThat(tenantService.getTenantStatus(tenantWithStatus(TenantStatus.SUSPENDED))).contains(TenantStatus.SUSPENDED);
        assertThat(tenantService.getTenantStatus(tenantWithStatus(TenantStatus.REVOKED))).contains(TenantStatus.REVOKED);
    }

    @Test
    @DisplayName("T1 returns empty for a missing tenant")
    void missingTenantIsEmpty() {
        assertThat(tenantService.getTenantStatus(UUID.randomUUID())).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("T1 reflects a status change immediately (no cache)")
    void observesStatusChangeImmediately() {
        UUID id = tenantWithStatus(TenantStatus.ACTIVE);
        assertThat(tenantService.getTenantStatus(id)).contains(TenantStatus.ACTIVE);

        TenantEntity tenant = tenantRepository.findById(id).orElseThrow();
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.saveAndFlush(tenant);

        assertThat(tenantService.getTenantStatus(id)).contains(TenantStatus.SUSPENDED);
    }
}
