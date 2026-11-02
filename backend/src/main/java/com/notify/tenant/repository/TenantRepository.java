package com.notify.tenant.repository;

import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    Optional<TenantEntity> findBySlug(String slug);

    /** Resolve demo tenants by configured slug list (/ demo feed). */
    List<TenantEntity> findBySlugIn(Collection<String> slugs);

    /**
     * The tenant row, locked for the transaction (/ , ).
     *
     * <p>First in the project lock order, so an admin DLQ replay and a tenant status change serialize on this row
     * instead of racing: whichever locks it first decides, and the other sees the committed result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TenantEntity t WHERE t.id = :id")
    Optional<TenantEntity> findByIdForUpdate(@Param("id") UUID id);

    // ─── Admin tenant listing with pagination and filtering ────────────────────────────────────────

    Page<TenantEntity> findAll(Pageable pageable);

    Page<TenantEntity> findByStatus(TenantStatus status, Pageable pageable);

    @Query("""
        SELECT t FROM TenantEntity t
        WHERE LOWER(t.slug) LIKE LOWER(CONCAT('%', :query, '%'))
           OR LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%'))
    """)
    Page<TenantEntity> findByQuery(@Param("query") String query, Pageable pageable);

    @Query("""
        SELECT t FROM TenantEntity t
        WHERE t.status = :status
          AND (LOWER(t.slug) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(t.name) LIKE LOWER(CONCAT('%', :query, '%')))
    """)
    Page<TenantEntity> findByStatusAndQuery(@Param("status") TenantStatus status,
                                             @Param("query") String query,
                                             Pageable pageable);
}
