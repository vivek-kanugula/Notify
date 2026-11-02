package com.notify.admin.repository;

import com.notify.admin.entity.AdminAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLogEntity, Long>,
        JpaSpecificationExecutor<AdminAuditLogEntity> {

    List<AdminAuditLogEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<AdminAuditLogEntity> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);

    long countByTenantIdAndAction(UUID tenantId, String action);
}
