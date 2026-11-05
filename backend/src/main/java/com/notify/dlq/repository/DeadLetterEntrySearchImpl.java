package com.notify.dlq.repository;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.tenant.entity.TenantEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Criteria implementation of {@link DeadLetterEntrySearch}: newest first, keyset on (created_at, id). */
public class DeadLetterEntrySearchImpl implements DeadLetterEntrySearch {

    private final EntityManager entityManager;

    public DeadLetterEntrySearchImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<DeadLetterEntryEntity> searchEntries(
            DlqStatus status, Channel channel, DlqReason reason, UUID tenantId, String tenantName,
            Instant from, Instant to, Instant cursorAt, UUID cursorId, int limit
    ) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<DeadLetterEntryEntity> query = builder.createQuery(DeadLetterEntryEntity.class);
        Root<DeadLetterEntryEntity> entry = query.from(DeadLetterEntryEntity.class);

        List<Predicate> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add(builder.equal(entry.get("status"), status));
        }
        if (channel != null) {
            predicates.add(builder.equal(entry.get("channel"), channel));
        }
        if (reason != null) {
            predicates.add(builder.equal(entry.get("reason"), reason));
        }
        if (tenantId != null) {
            predicates.add(builder.equal(entry.get("tenantId"), tenantId));
        }
        if (tenantName != null && !tenantName.isBlank()) {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<TenantEntity> tenantRoot = subquery.from(TenantEntity.class);
            String pattern = "%" + tenantName.trim().toLowerCase() + "%";
            subquery.select(tenantRoot.get("id"))
                    .where(builder.or(
                            builder.like(builder.lower(tenantRoot.get("name")), pattern),
                            builder.like(builder.lower(tenantRoot.get("slug")), pattern)
                    ));
            predicates.add(entry.get("tenantId").in(subquery));
        }
        if (from != null) {
            predicates.add(builder.greaterThanOrEqualTo(entry.get("createdAt"), from));
        }
        if (to != null) {
            predicates.add(builder.lessThanOrEqualTo(entry.get("createdAt"), to));
        }
        if (cursorAt != null && cursorId != null) {
            // Strictly after the previous page, in the same (created_at desc, id desc) order.
            predicates.add(builder.or(
                    builder.lessThan(entry.get("createdAt"), cursorAt),
                    builder.and(
                            builder.equal(entry.get("createdAt"), cursorAt),
                            builder.lessThan(entry.get("id"), cursorId))));
        }

        query.where(predicates.toArray(new Predicate[0]))
                .orderBy(builder.desc(entry.get("createdAt")), builder.desc(entry.get("id")));

        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}
