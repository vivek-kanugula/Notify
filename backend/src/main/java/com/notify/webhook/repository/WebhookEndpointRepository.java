package com.notify.webhook.repository;

import com.notify.webhook.entity.WebhookEndpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One endpoint per tenant (`webhook_endpoints.tenant_id` is UNIQUE), so every lookup is tenant-scoped by construction
 * and no cross-tenant read is expressible here.
 */
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, UUID> {

    Optional<WebhookEndpointEntity> findByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);

    /**
     * Creates the endpoint only if the tenant has none (A2).
     *
     * <p>Deliberately an {@code ON CONFLICT DO NOTHING} insert rather than a caught constraint violation: PostgreSQL
     * aborts the whole transaction on a failed constraint, so catching the violation and carrying on inside the same
     * transaction fails with "current transaction is aborted". This statement reports the race through its row count
     * instead, leaving the transaction usable. It needs no new column and no migration — the existing
     * {@code UNIQUE (tenant_id)} does the work.
     *
     * @return 1 when this call created the row, 0 when another transaction already had
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO webhook_endpoints
            (id, tenant_id, url, is_demo, enabled, signing_secret_enc, status_callbacks, created_at, updated_at)
        VALUES (:id, :tenantId, :url, :demo, :enabled, :secret, false, :now, :now)
        ON CONFLICT (tenant_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("url") String url,
            @Param("demo") boolean demo,
            @Param("enabled") boolean enabled,
            @Param("secret") String secret,
            @Param("now") Instant now
    );

    /**
     * Writes only the configuration columns (A2). A full entity save would also rewrite
     * {@code signing_secret_enc} from a snapshot read before a concurrent rotation, silently restoring the old
     * secret; this statement cannot, because it never mentions that column.
     *
     * @return 1 when the row was updated, 0 when the tenant has no endpoint
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WebhookEndpointEntity e
        SET e.url = :url,
            e.isDemo = :demo,
            e.enabled = :enabled,
            e.updatedAt = :now
        WHERE e.tenantId = :tenantId
    """)
    int updateConfiguration(
            @Param("tenantId") UUID tenantId,
            @Param("url") String url,
            @Param("demo") boolean demo,
            @Param("enabled") boolean enabled,
            @Param("now") Instant now
    );

    /**
     * Writes only the signing secret (A2), so a rotation cannot overwrite a URL or the enabled flag that another
     * request changed in parallel.
     *
     * @return 1 when the row was updated, 0 when the tenant has no endpoint
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WebhookEndpointEntity e
        SET e.signingSecretEnc = :secret,
            e.updatedAt = :now
        WHERE e.tenantId = :tenantId
    """)
    int updateSigningSecret(
            @Param("tenantId") UUID tenantId,
            @Param("secret") String secret,
            @Param("now") Instant now
    );
}
