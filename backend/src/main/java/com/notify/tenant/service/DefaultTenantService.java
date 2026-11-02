package com.notify.tenant.service;

import com.notify.common.model.TenantStatus;
import com.notify.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultTenantService implements TenantService {

    private final TenantRepository tenantRepository;

    public DefaultTenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional(readOnly = true, timeout = 2)
    public Optional<TenantStatus> getTenantStatus(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(tenant -> tenant.getStatus());
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<TenantStatus> getTenantStatusForUpdate(UUID tenantId) {
        return tenantRepository.findByIdForUpdate(tenantId).map(tenant -> tenant.getStatus());
    }
}
