package com.notify.dlq.service;

import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.dlq.config.DlqConsumerProperties;
import com.notify.dlq.dto.DlqNotificationSnapshot;
import com.notify.dlq.dto.DlqPage;
import com.notify.dlq.dto.QuarantineDetail;
import com.notify.dlq.dto.QuarantineSummary;
import com.notify.dlq.entity.DlqQuarantineEntity;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.repository.DlqQuarantineRepository;
import com.notify.dlq.service.AdminDlqService.AdminActor;
import com.notify.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only inspection and resolution of quarantined records (/ , ).
 *
 * <p><b>It works when no {@code dead_letter_entries} row exists</b>, which is the whole point: an unparsable,
 * unknown or mismatched record has no business entry to hang off, and under even a verified one has none.
 *
 * <p><b>No replay exists here.</b> A record with no verified association cannot be replayed without inventing a
 * target; a verified one is replayed through its notification entry when there is one. The only resolution is
 * reviewed-and-closed, with a required note.
 *
 * <p>The projection separates <b>claimed</b> values (untrusted payload copies) from <b>verified</b> ones (database
 * lookups), and the notification snapshot it shows is read now - no historical cycle is reconstructed, because the
 * dispatch envelope never carried one.
 */
@Service
public class AdminQuarantineService {

    private static final Logger log = LoggerFactory.getLogger(AdminQuarantineService.class);

    private final DlqQuarantineRepository quarantine;
    private final DeadLetterEntryRepository entries;
    private final NotificationService notificationService;
    private final AdminAuditLogRepository auditLog;
    private final DlqConsumerProperties props;
    private final Clock clock;

    public AdminQuarantineService(
            DlqQuarantineRepository quarantine,
            DeadLetterEntryRepository entries,
            NotificationService notificationService,
            AdminAuditLogRepository auditLog,
            DlqConsumerProperties props,
            Clock clock
    ) {
        this.quarantine = quarantine;
        this.entries = entries;
        this.notificationService = notificationService;
        this.auditLog = auditLog;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DlqPage<QuarantineSummary> list(
            QuarantineAssociation association, QuarantineStatus status, String topic, String dltReason,
            Instant from, Instant to, DlqCursor cursor, int limit
    ) {
        int effective = boundedLimit(limit);
        List<DlqQuarantineEntity> rows = quarantine.searchQuarantine(
                association, status, topic, dltReason, from, to,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(),
                effective + 1);
        boolean more = rows.size() > effective;
        List<DlqQuarantineEntity> page = more ? rows.subList(0, effective) : rows;
        String next = more && !page.isEmpty()
                ? DlqCursor.of(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId()).encode()
                : null;
        return new DlqPage<>(page.stream().map(AdminQuarantineService::toSummary).toList(), next);
    }

    @Transactional(readOnly = true)
    public QuarantineDetail detail(UUID quarantineId) {
        DlqQuarantineEntity row = require(quarantineId);
        DlqNotificationSnapshot snapshot = null;
        UUID relatedEntryId = null;
        long related = 0;
        if (row.getVerifiedNotificationId() != null && row.getVerifiedTenantId() != null) {
            snapshot = notificationService
                    .findNotificationSnapshot(row.getVerifiedTenantId(), row.getVerifiedNotificationId())
                    .orElse(null);
            relatedEntryId = entries.findByNotificationId(row.getVerifiedNotificationId())
                    .map(entry -> entry.getId()).orElse(null);
            related = quarantine.countByVerifiedNotificationId(row.getVerifiedNotificationId());
        }
        return new QuarantineDetail(
                row.getId(), row.getTopic(), row.getPartitionNumber(), row.getRecordOffset(),
                row.getDltReason(), row.getErrorCategory(), row.getExceptionClass(),
                row.getPayloadSha256(), row.getPayloadByteCount(), row.getAssociation(), row.getStatus(),
                row.getClaimedNotificationId(), row.getClaimedTenantId(), row.getClaimedGeneration(),
                row.getVerifiedNotificationId(), row.getVerifiedTenantId(),
                snapshot, relatedEntryId, related,
                row.getResolutionNote(), row.getResolvedBy(), row.getResolvedAt(), row.getCreatedAt());
    }

    /** Reviewed and closed. Conditional on OPEN, so two concurrent resolutions give exactly one winner. */
    @Transactional
    public QuarantineDetail resolve(UUID quarantineId, String note, AdminActor actor) {
        DlqQuarantineEntity row = require(quarantineId);
        int rows = quarantine.resolveConditional(quarantineId, note, actor.email(), clock.instant());
        if (rows == 0) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Quarantined record is no longer OPEN; resolution refused");
        }
        AdminAuditLogEntity audit = new AdminAuditLogEntity();
        audit.setAdminId(actor.adminId());
        audit.setAdminEmail(actor.email());
        // Reuses the existing action value: the V1 CHECK is a closed list, and a new label would need a migration.
        audit.setAction("DLQ_DISCARDED");
        audit.setTargetType("DLQ_QUARANTINE");
        audit.setTargetId(quarantineId.toString());
        audit.setTenantId(row.getVerifiedTenantId());
        audit.setReason(note);
        audit.setDetails(auditDetails(row));
        audit.setIpAddress(actor.ipAddress());
        audit.setCreatedAt(clock.instant());
        auditLog.save(audit);
        log.info("Resolved quarantined record {} ({}-{} offset {}) by {}", quarantineId, row.getTopic(),
                row.getPartitionNumber(), row.getRecordOffset(), actor.email());
        return detail(quarantineId);
    }

    /** Coordinates and classification only - no payload, no claimed body, no secret. */
    private static String auditDetails(DlqQuarantineEntity row) {
        return "{\"topic\":\"" + row.getTopic()
                + "\",\"partition\":" + row.getPartitionNumber()
                + ",\"offset\":" + row.getRecordOffset()
                + ",\"association\":\"" + row.getAssociation() + "\"}";
    }

    private DlqQuarantineEntity require(UUID quarantineId) {
        return quarantine.findById(quarantineId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Quarantined record not found"));
    }

    private int boundedLimit(int limit) {
        return limit <= 0 ? props.api().defaultLimit() : Math.min(limit, props.api().maxLimit());
    }

    private static QuarantineSummary toSummary(DlqQuarantineEntity row) {
        return new QuarantineSummary(
                row.getId(), row.getTopic(), row.getPartitionNumber(), row.getRecordOffset(),
                row.getDltReason(), row.getErrorCategory(), row.getAssociation(), row.getStatus(),
                row.getClaimedNotificationId(), row.getClaimedTenantId(),
                row.getVerifiedNotificationId(), row.getVerifiedTenantId(), row.getCreatedAt());
    }
}
