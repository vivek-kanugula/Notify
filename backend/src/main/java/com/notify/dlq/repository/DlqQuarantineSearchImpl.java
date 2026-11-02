package com.notify.dlq.repository;

import com.notify.dlq.entity.DlqQuarantineEntity;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Criteria implementation of {@link DlqQuarantineSearch}: newest first, keyset on (created_at, id). */
public class DlqQuarantineSearchImpl implements DlqQuarantineSearch {

    private final EntityManager entityManager;

    public DlqQuarantineSearchImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<DlqQuarantineEntity> searchQuarantine(
            QuarantineAssociation association, QuarantineStatus status, String topic, String dltReason,
            Instant from, Instant to, Instant cursorAt, UUID cursorId, int limit
    ) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<DlqQuarantineEntity> query = builder.createQuery(DlqQuarantineEntity.class);
        Root<DlqQuarantineEntity> row = query.from(DlqQuarantineEntity.class);

        List<Predicate> predicates = new ArrayList<>();
        if (association != null) {
            predicates.add(builder.equal(row.get("association"), association));
        }
        if (status != null) {
            predicates.add(builder.equal(row.get("status"), status));
        }
        if (topic != null && !topic.isBlank()) {
            predicates.add(builder.equal(row.get("topic"), topic));
        }
        if (dltReason != null && !dltReason.isBlank()) {
            predicates.add(builder.equal(row.get("dltReason"), dltReason));
        }
        if (from != null) {
            predicates.add(builder.greaterThanOrEqualTo(row.get("createdAt"), from));
        }
        if (to != null) {
            predicates.add(builder.lessThanOrEqualTo(row.get("createdAt"), to));
        }
        if (cursorAt != null && cursorId != null) {
            predicates.add(builder.or(
                    builder.lessThan(row.get("createdAt"), cursorAt),
                    builder.and(
                            builder.equal(row.get("createdAt"), cursorAt),
                            builder.lessThan(row.get("id"), cursorId))));
        }

        query.where(predicates.toArray(new Predicate[0]))
                .orderBy(builder.desc(row.get("createdAt")), builder.desc(row.get("id")));

        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}
