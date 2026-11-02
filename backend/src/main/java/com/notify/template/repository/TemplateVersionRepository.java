package com.notify.template.repository;

import com.notify.common.model.TemplateVersionStatus;
import com.notify.template.entity.TemplateVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TemplateVersionRepository extends JpaRepository<TemplateVersionEntity, UUID> {

    List<TemplateVersionEntity> findByTenantIdAndTemplateIdOrderByVersionNumberDesc(UUID tenantId, UUID templateId);

    Optional<TemplateVersionEntity> findByTenantIdAndTemplateIdAndId(UUID tenantId, UUID templateId, UUID id);

    Optional<TemplateVersionEntity> findByTenantIdAndTemplateIdAndVersionNumber(UUID tenantId, UUID templateId, Integer versionNumber);

    Optional<TemplateVersionEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<TemplateVersionEntity> findByTemplateIdAndStatus(UUID templateId, TemplateVersionStatus status);

    @Query("SELECT MAX(v.versionNumber) FROM TemplateVersionEntity v WHERE v.templateId = :templateId")
    Optional<Integer> findMaxVersionNumberByTemplateId(@Param("templateId") UUID templateId);
}
