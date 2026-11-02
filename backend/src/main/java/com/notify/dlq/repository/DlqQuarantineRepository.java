package com.notify.dlq.repository;

import com.notify.dlq.entity.DlqQuarantineEntity;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Quarantined records (). Keyset-paged like the existing finders; every filter is optional. */
@Repository
public interface DlqQuarantineRepository
        extends JpaRepository<DlqQuarantineEntity, UUID>, DlqQuarantineSearch {

    Optional<DlqQuarantineEntity> findByTopicAndPartitionNumberAndRecordOffset(
            String topic, int partitionNumber, long recordOffset);

    long countByVerifiedNotificationId(UUID verifiedNotificationId);

    List<DlqQuarantineEntity> findByVerifiedNotificationIdOrderByCreatedAtDesc(
            UUID verifiedNotificationId, Pageable pageable);

    /**
     * Resolves one OPEN quarantine row. Conditional on the current status, so two concurrent resolutions produce
     * exactly one winner and the loser sees zero rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE DlqQuarantineEntity q
        SET q.status = com.notify.dlq.model.QuarantineStatus.DISCARDED,
            q.resolutionNote = :note,
            q.resolvedBy = :resolvedBy,
            q.resolvedAt = :now
        WHERE q.id = :id
          AND q.status = com.notify.dlq.model.QuarantineStatus.OPEN
    """)
    int resolveConditional(
            @Param("id") UUID id,
            @Param("note") String note,
            @Param("resolvedBy") String resolvedBy,
            @Param("now") Instant now
    );
}
