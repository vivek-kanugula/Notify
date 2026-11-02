package com.notify.auth.repository;

import com.notify.auth.entity.RefreshTokenEntity;
import com.notify.common.model.PrincipalType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    List<RefreshTokenEntity> findByFamilyIdAndRevokedAtIsNull(UUID familyId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :now WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :now WHERE r.principalType = :type AND r.principalId = :principalId AND r.revokedAt IS NULL")
    int revokeAllForPrincipal(@Param("type") PrincipalType type, @Param("principalId") UUID principalId, @Param("now") Instant now);

    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.principalType = :type AND r.principalId = :principalId AND r.revokedAt IS NULL AND r.expiresAt > :now")
    List<RefreshTokenEntity> findActiveByPrincipal(@Param("type") PrincipalType type, @Param("principalId") UUID principalId, @Param("now") Instant now);

    // ─── bulk revoke / delete for tenant revocation and deletion ────────────────────────────────────

    /**
     * Bulk-revoke all active refresh tokens for the given principal IDs (revocation cascade).
     * Used when revoking a tenant: all tenant users' sessions become invalid.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :now WHERE r.principalType = :type AND r.principalId IN :principalIds AND r.revokedAt IS NULL")
    int revokeAllForPrincipals(@Param("type") PrincipalType type, @Param("principalIds") List<UUID> principalIds, @Param("now") Instant now);

    /**
     * Bulk-delete all refresh tokens for the given principal IDs (deletion cleanup).
     * Must run before tenant deletion since refresh_tokens has no FK to tenants.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RefreshTokenEntity r WHERE r.principalType = :type AND r.principalId IN :principalIds")
    int deleteAllForPrincipals(@Param("type") PrincipalType type, @Param("principalIds") List<UUID> principalIds);

    /**
     * Count remaining tokens for verification after deletion (post-delete assertion).
     */
    @Query("SELECT COUNT(r) FROM RefreshTokenEntity r WHERE r.principalType = :type AND r.principalId IN :principalIds")
    long countByPrincipalTypeAndPrincipalIds(@Param("type") PrincipalType type, @Param("principalIds") List<UUID> principalIds);

    /**
     * Delete all polymorphic refresh tokens for users belonging to the tenant via direct subquery.
     * Prevents race conditions where user IDs might be added during deletion ().
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        DELETE FROM refresh_tokens
        WHERE principal_type = 'TENANT_USER'
          AND principal_id IN (SELECT id FROM tenant_users WHERE tenant_id = :tenantId)
    """, nativeQuery = true)
    int deleteAllForTenantUsers(@Param("tenantId") UUID tenantId);

    /**
     * Bulk-revoke all polymorphic refresh tokens for users belonging to the tenant via direct subquery.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE refresh_tokens
        SET revoked_at = :now
        WHERE principal_type = 'TENANT_USER'
          AND principal_id IN (SELECT id FROM tenant_users WHERE tenant_id = :tenantId)
          AND revoked_at IS NULL
    """, nativeQuery = true)
    int revokeAllForTenantUsers(@Param("tenantId") UUID tenantId, @Param("now") Instant now);
}
