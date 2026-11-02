package com.notify.admin.service;

import com.notify.admin.dto.AdminAuditLogResponse;
import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Query service for the immutable admin audit log.
 * Supports paginated listing with optional filters by tenantId, action, targetType, and date range.
 */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository adminAuditLogRepository;

    public AdminAuditService(AdminAuditLogRepository adminAuditLogRepository) {
        this.adminAuditLogRepository = adminAuditLogRepository;
    }

    /**
     * List audit log entries with optional filtering.
     *
     * @param tenantId   optional tenant ID filter
     * @param action     optional action filter (e.g. TENANT_REVOKED, TENANT_DELETED)
     * @param targetType optional target type filter (e.g. TENANT)
     * @param from       optional start of date range (inclusive)
     * @param to         optional end of date range (inclusive)
     * @param page       zero-based page number
     * @param size       page size (1..100)
     * @return paginated audit log entries
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLogResponse> listAuditLog(UUID tenantId, String action, String targetType,
                                                     Instant from, Instant to,
                                                     int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AdminAuditLogEntity> spec = buildSpecification(tenantId, action, targetType, from, to);

        return adminAuditLogRepository.findAll(spec, pageable)
                .map(this::toResponse);
    }

    private Specification<AdminAuditLogEntity> buildSpecification(UUID tenantId, String action,
                                                                   String targetType,
                                                                   Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (tenantId != null) {
                predicates.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (targetType != null && !targetType.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), targetType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AdminAuditLogResponse toResponse(AdminAuditLogEntity entity) {
        return new AdminAuditLogResponse(
                entity.getId(),
                entity.getAdminId(),
                entity.getAdminEmail(),
                entity.getAction(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getTenantId(),
                entity.getTenantSlug(),
                entity.getReason(),
                entity.getDetails(),
                entity.getIpAddress(),
                entity.getCreatedAt()
        );
    }
}
