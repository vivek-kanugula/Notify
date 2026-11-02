package com.notify.apikey.repository;

import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.common.model.ApiKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    /**
     * Find active key by hash for API key authentication hot path.
     * Includes expiry check (null or future).
     */
    @Query("""
            SELECT k FROM ApiKeyEntity k
            WHERE k.keyHash = :hash
              AND k.status = 'ACTIVE'
              AND (k.expiresAt IS NULL OR k.expiresAt > :now)
            """)
    Optional<ApiKeyEntity> findActiveByHash(@Param("hash") String hash, @Param("now") Instant now);

    /**
     * Find key by hash regardless of status/expiry.
     */
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    /**
     * Count active, unexpired keys for a tenant (for the 10-key limit).
     * Excludes keys in rotation overlap (where rotatedToId is set) and keys past expiresAt.
     */
    @Query("""
            SELECT COUNT(k) FROM ApiKeyEntity k
            WHERE k.tenantId = :tenantId AND k.status = 'ACTIVE' AND k.rotatedToId IS NULL
              AND (k.expiresAt IS NULL OR k.expiresAt > :now)
            """)
    long countActiveByTenantId(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    default long countActiveByTenantId(UUID tenantId) {
        return countActiveByTenantId(tenantId, Instant.now());
    }

    /**
     * Tenant-scoped list of all keys (not deleted, ordered by creation desc).
     */
    List<ApiKeyEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /**
     * Tenant-scoped key lookup by id (for management endpoints).
     * Returns regardless of status so owner can see revoked keys.
     */
    Optional<ApiKeyEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Revoke a key: atomic status update with precondition (must be ACTIVE).
     * Returns count of updated rows; 0 means already revoked or not found.
     */
    @Modifying
    @Query("""
            UPDATE ApiKeyEntity k
            SET k.status = :newStatus, k.updatedAt = :now
            WHERE k.id = :id AND k.tenantId = :tenantId AND k.status = 'ACTIVE'
            """)
    int revokeKey(@Param("id") UUID id,
                  @Param("tenantId") UUID tenantId,
                  @Param("newStatus") ApiKeyStatus newStatus,
                  @Param("now") Instant now);

    /**
     * Throttled last-used update: only update if not updated in the last 60 seconds.
     */
    @Modifying
    @Query("""
            UPDATE ApiKeyEntity k
            SET k.lastUsedAt = :now, k.lastUsedIp = :ip, k.updatedAt = :now
            WHERE k.id = :id
              AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :threshold)
            """)
    int updateLastUsed(@Param("id") UUID id,
                       @Param("now") Instant now,
                       @Param("ip") String ip,
                       @Param("threshold") Instant threshold);

    // ─── bulk revoke for tenant revocation cascade ─────────────────────────────────────────────────

    /**
     * Bulk-revoke all active API keys for a tenant (revocation cascade).
     *
     * @return count of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ApiKeyEntity k
            SET k.status = com.notify.common.model.ApiKeyStatus.REVOKED,
                k.updatedAt = :now
            WHERE k.tenantId = :tenantId AND k.status = com.notify.common.model.ApiKeyStatus.ACTIVE
            """)
    int revokeAllByTenantId(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    /**
     * Retrieve all key hashes for a tenant so that Redis cache entries can be evicted
     * in an afterCommit hook ().
     */
    @Query("SELECT k.keyHash FROM ApiKeyEntity k WHERE k.tenantId = :tenantId")
    List<String> findKeyHashesByTenantId(@Param("tenantId") UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, ApiKeyStatus status);
}
