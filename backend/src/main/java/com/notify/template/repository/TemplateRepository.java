package com.notify.template.repository;

import com.notify.template.entity.TemplateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, UUID> {

    List<TemplateEntity> findByTenantIdAndDeletedAtIsNull(UUID tenantId, Sort sort);

    Optional<TemplateEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<TemplateEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TemplateEntity t WHERE t.id = :id AND t.tenantId = :tenantId")
    Optional<TemplateEntity> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(UUID tenantId, String name);

    @Query("SELECT COUNT(t) > 0 FROM TemplateEntity t WHERE t.tenantId = :tenantId AND LOWER(t.name) = LOWER(:name) AND t.id <> :excludeId AND t.deletedAt IS NULL")
    boolean existsByTenantIdAndNameIgnoreCaseAndIdNotAndDeletedAtIsNull(@Param("tenantId") UUID tenantId,
                                                                        @Param("name") String name,
                                                                        @Param("excludeId") UUID excludeId);

    // ─── aggregate counts for admin tenant detail ──────────────────────────────────────────────────

    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
