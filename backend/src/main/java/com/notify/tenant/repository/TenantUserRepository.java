package com.notify.tenant.repository;

import com.notify.tenant.entity.TenantUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantUserRepository extends JpaRepository<TenantUserEntity, UUID> {

    Optional<TenantUserEntity> findByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Look up tenant user by email.
     */
    Optional<TenantUserEntity> findByEmail(String email);

    Optional<TenantUserEntity> findByEmailAndPasswordHashIsNotNull(String email);

    // ─── aggregate counts and pre-deletion ID capture ──────────────────────────────────────────────

    long countByTenantId(UUID tenantId);

    /**
     * Capture all user IDs for a tenant before deletion so that orphaned polymorphic
     * {@code refresh_tokens} can be explicitly cleaned ().
     */
    @Query("SELECT u.id FROM TenantUserEntity u WHERE u.tenantId = :tenantId")
    List<UUID> findIdsByTenantId(@Param("tenantId") UUID tenantId);
}

