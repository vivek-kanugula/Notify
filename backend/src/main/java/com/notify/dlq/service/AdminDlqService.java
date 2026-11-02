package com.notify.dlq.service;

import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.common.model.TenantStatus;
import com.notify.delivery.service.DeliveryAttemptReader;
import com.notify.dlq.config.DlqConsumerProperties;
import com.notify.dlq.dto.DlqAttemptView;
import com.notify.dlq.dto.DlqEntryDetail;
import com.notify.dlq.dto.DlqEntrySummary;
import com.notify.dlq.dto.DlqHistoryView;
import com.notify.dlq.dto.DlqNotificationSnapshot;
import com.notify.dlq.dto.DlqPage;
import com.notify.dlq.dto.DlqReplayResponse;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.repository.DlqQuarantineRepository;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-only dead-letter inspection and resolution (/ , ).
 *
 * <p><b>Service boundaries.</b> It owns the two DLQ repositories and nothing else: notification reads and
 * mutations go through {@link NotificationService}, attempt history through {@link DeliveryAttemptReader}, tenant
 * eligibility through {@link TenantService}. It never touches {@code NotificationRepository}, a notification
 * entity, an attempt entity or a history entity.
 *
 * <p><b>Replay is one transaction</b>: tenant eligibility re-read under a row lock, the conditional DLQ to QUEUED
 * transition with a new cycle and generation, the entry transition carrying the single {@code replay_count}
 * increment (), the history row, the audit row and the {@code NOTIFICATION_REPLAYED} outbox event. Any step
 * that updates no row rolls the whole thing back and answers 409.
 */
@Service
public class AdminDlqService {

    private static final Logger log = LoggerFactory.getLogger(AdminDlqService.class);

    static final String AMBIGUITY_NOTE =
            "A replay starts a new delivery cycle with a new provider send identity, so a receiver cannot "
                    + "deduplicate it against the previous cycle. If an earlier attempt was accepted remotely "
                    + "(a timeout, an abandoned read, or work recovered after a worker loss), this replay can "
                    + "produce a second external effect. Exactly-once delivery is not claimed.";

    private final DeadLetterEntryRepository entries;
    private final DlqQuarantineRepository quarantine;
    private final NotificationService notificationService;
    private final DeliveryAttemptReader attemptReader;
    private final TenantService tenantService;
    private final TenantRepository tenants;
    private final AdminAuditLogRepository auditLog;
    private final DlqConsumerProperties props;
    private final Clock clock;

    public AdminDlqService(
            DeadLetterEntryRepository entries,
            DlqQuarantineRepository quarantine,
            NotificationService notificationService,
            DeliveryAttemptReader attemptReader,
            TenantService tenantService,
            TenantRepository tenants,
            AdminAuditLogRepository auditLog,
            DlqConsumerProperties props,
            Clock clock
    ) {
        this.entries = entries;
        this.quarantine = quarantine;
        this.notificationService = notificationService;
        this.attemptReader = attemptReader;
        this.tenantService = tenantService;
        this.tenants = tenants;
        this.auditLog = auditLog;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DlqPage<DlqEntrySummary> list(
            DlqStatus status, Channel channel, DlqReason reason, UUID tenantId,
            Instant from, Instant to, DlqCursor cursor, int limit
    ) {
        int effective = boundedLimit(limit);
        List<DeadLetterEntryEntity> rows = entries.searchEntries(
                status, channel, reason, tenantId, from, to,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(),
                effective + 1);
        boolean more = rows.size() > effective;
        List<DeadLetterEntryEntity> page = more ? rows.subList(0, effective) : rows;
        List<DlqEntrySummary> items = page.stream().map(this::toSummary).toList();
        String next = more && !page.isEmpty()
                ? DlqCursor.of(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId()).encode()
                : null;
        return new DlqPage<>(items, next);
    }

    @Transactional(readOnly = true)
    public DlqEntryDetail detail(UUID entryId) {
        DeadLetterEntryEntity entry = requireEntry(entryId);
        int embedded = props.api().detailEmbeddedLimit();

        List<DlqAttemptView> allAttempts =
                attemptReader.attemptsFor(entry.getTenantId(), entry.getNotificationId());
        List<DlqHistoryView> allHistory =
                notificationService.historyFor(entry.getTenantId(), entry.getNotificationId());
        DlqNotificationSnapshot snapshot = notificationService
                .findNotificationSnapshot(entry.getTenantId(), entry.getNotificationId())
                .orElse(null);

        return new DlqEntryDetail(
                entry.getId(), entry.getNotificationId(), entry.getTenantId(), tenantSlug(entry.getTenantId()),
                entry.getChannel(), entry.getReason(), entry.getLastErrorCode(), entry.getAttemptCount(),
                entry.getStatus(), entry.getReplayCount(), entry.getSourceDeliveryCycle(),
                entry.getSourceGeneration(), entry.getPayloadSnapshot(), entry.getResolutionNote(),
                entry.getResolvedBy(), entry.getResolvedAt(), entry.getCreatedAt(), entry.getUpdatedAt(),
                snapshot,
                allAttempts.stream().limit(embedded).toList(), allAttempts.size(), allAttempts.size() > embedded,
                allHistory.stream().limit(embedded).toList(), allHistory.size(), allHistory.size() > embedded,
                quarantine.countByVerifiedNotificationId(entry.getNotificationId()));
    }

    @Transactional(readOnly = true)
    public DlqPage<DlqAttemptView> attempts(UUID entryId, int limit) {
        DeadLetterEntryEntity entry = requireEntry(entryId);
        List<DlqAttemptView> all = attemptReader.attemptsFor(entry.getTenantId(), entry.getNotificationId());
        return new DlqPage<>(all.stream().limit(boundedLimit(limit)).toList(), null);
    }

    @Transactional(readOnly = true)
    public DlqPage<DlqHistoryView> history(UUID entryId, int limit) {
        DeadLetterEntryEntity entry = requireEntry(entryId);
        List<DlqHistoryView> all = notificationService.historyFor(entry.getTenantId(), entry.getNotificationId());
        return new DlqPage<>(all.stream().limit(boundedLimit(limit)).toList(), null);
    }

    /**
     * Replays one OPEN entry ().
     *
     * <p>Order inside the single transaction, which is also the project lock order: the tenant row, the
     * notification row, the entry row, then the append-only writes. Tenant eligibility is therefore decided
     * <b>inside</b> the transaction, not in a pre-check a concurrent suspension could invalidate.
     */
    @Transactional
    public DlqReplayResponse replay(UUID entryId, String note, AdminActor actor) {
        DeadLetterEntryEntity entry = requireEntry(entryId);

        // 1. tenants - the lock that serializes a replay against a concurrent suspension or revocation.
        TenantStatus tenantStatus = tenantService.getTenantStatusForUpdate(entry.getTenantId())
                .orElseThrow(() -> new NotifyException(
                        ErrorCode.INVALID_STATE_TRANSITION, "Tenant no longer exists; replay refused"));
        if (tenantStatus != TenantStatus.ACTIVE) {
            // Not TENANT_SUSPENDED/TENANT_REVOKED: those describe the caller tenant, and the caller is an admin.
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Tenant is " + tenantStatus + "; a replay would be refused again at the worker gate");
        }

        // 2. notifications - new cycle, new generation, cycle budgets reset, claim_seq preserved.
        Optional<NotificationService.ReplayDispatch> dispatch = notificationService.replayFromDlq(
                entry.getTenantId(), entry.getNotificationId(), requireGeneration(entry));
        if (dispatch.isEmpty()) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Notification is not in DLQ at the expected generation; replay refused");
        }

        // 3. dead_letter_entries - the single replay_count increment lives here ().
        int rows = entries.resolveOpenConditional(
                entryId, entry.getTenantId(), DlqStatus.REPLAYED, 1, note, actor.email(), clock.instant());
        if (rows == 0) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Dead-letter entry is no longer OPEN; replay refused");
        }

        // 4. append-only: the audit row, in the same transaction as the state change.
        NotificationService.ReplayDispatch replayed = dispatch.get();
        audit("DLQ_REPLAYED", entry, actor, note,
                "{\"newDeliveryCycle\":" + replayed.deliveryCycle()
                        + ",\"newDispatchGeneration\":" + replayed.dispatchGeneration() + "}");

        log.info("Replayed dead-letter entry {} for notification {} into cycle {} (generation {}) by {}",
                entryId, entry.getNotificationId(), replayed.deliveryCycle(), replayed.dispatchGeneration(),
                actor.email());

        return new DlqReplayResponse(
                entryId, entry.getNotificationId(), DlqStatus.REPLAYED, entry.getReplayCount() + 1,
                replayed.deliveryCycle(), replayed.dispatchGeneration(),
                possibleDuplicateDelivery(entry), AMBIGUITY_NOTE);
    }

    /**
     * Discards one OPEN entry (, and ).
     *
     * <p>The notification status is <b>not</b> changed and <b>no</b> notification history row is written: nothing
     * about the notification lifecycle changed, and the entry plus the audit row carry the decision. The note is
     * required, because a discard throws work away.
     */
    @Transactional
    public DlqEntryDetail discard(UUID entryId, String note, AdminActor actor) {
        DeadLetterEntryEntity entry = requireEntry(entryId);
        int rows = entries.resolveOpenConditional(
                entryId, entry.getTenantId(), DlqStatus.DISCARDED, 0, note, actor.email(), clock.instant());
        if (rows == 0) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Dead-letter entry is no longer OPEN; discard refused");
        }
        audit("DLQ_DISCARDED", entry, actor, note, null);
        log.info("Discarded dead-letter entry {} for notification {} by {}",
                entryId, entry.getNotificationId(), actor.email());
        return detail(entryId);
    }

    /**
     * Whether this entry could have left an external effect behind that a replay may duplicate ().
     *
     * <p>True for an exhausted budget and for a timeout-class last error, which are exactly the cases where a
     * provider may have accepted a message Notify never saw confirmed. It is a hint for the operator, never a
     * claim that the other cases are duplicate-free - a replay always issues a new send identity.
     */
    private boolean possibleDuplicateDelivery(DeadLetterEntryEntity entry) {
        if (entry.getReason() == DlqReason.MAX_RETRIES_EXCEEDED) {
            return true;
        }
        String code = entry.getLastErrorCode();
        return code != null && (code.contains("TIMEOUT") || code.contains("UNAVAILABLE")
                || code.contains("UNREACHABLE") || code.equals("WORKER_LOST"));
    }

    private DeadLetterEntryEntity requireEntry(UUID entryId) {
        return entries.findById(entryId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Dead-letter entry not found"));
    }

    /**
     * The generation the replay predicate must match.
     *
     * <p>Read from the notification itself rather than from the entry: the entry records the generation of the
     * event that produced it, which is the same value while the row sits in DLQ, but the notification is the
     * authority for what the conditional update must match.
     */
    private int requireGeneration(DeadLetterEntryEntity entry) {
        return notificationService.findNotificationSnapshot(entry.getTenantId(), entry.getNotificationId())
                .map(DlqNotificationSnapshot::currentDispatchGeneration)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Notification not found"));
    }

    private void audit(
            String action, DeadLetterEntryEntity entry, AdminActor actor, String note, String details) {
        AdminAuditLogEntity row = new AdminAuditLogEntity();
        row.setAdminId(actor.adminId());
        row.setAdminEmail(actor.email());
        row.setAction(action);
        row.setTargetType("NOTIFICATION");
        row.setTargetId(entry.getNotificationId().toString());
        row.setTenantId(entry.getTenantId());
        row.setTenantSlug(tenantSlug(entry.getTenantId()));
        row.setReason(note);
        row.setDetails(details);
        row.setIpAddress(actor.ipAddress());
        row.setCreatedAt(clock.instant());
        auditLog.save(row);
    }

    /** The audit row carries a tenant snapshot, exactly as the tenant-status actions do. */
    private String tenantSlug(UUID tenantId) {
        return tenants.findById(tenantId).map(TenantEntity::getSlug).orElse(null);
    }

    private DlqEntrySummary toSummary(DeadLetterEntryEntity entry) {
        return new DlqEntrySummary(
                entry.getId(), entry.getNotificationId(), entry.getTenantId(), tenantSlug(entry.getTenantId()),
                entry.getChannel(), entry.getReason(), entry.getLastErrorCode(), entry.getAttemptCount(),
                entry.getStatus(), entry.getReplayCount(), entry.getCreatedAt(), entry.getUpdatedAt());
    }

    /** A limit of zero or less means "the configured default"; anything above the ceiling is rejected upstream. */
    private int boundedLimit(int limit) {
        if (limit <= 0) {
            return props.api().defaultLimit();
        }
        return Math.min(limit, props.api().maxLimit());
    }

    public int maxLimit() {
        return props.api().maxLimit();
    }

    /** Who performed an admin action: taken from the authenticated session, never from the request body. */
    public record AdminActor(UUID adminId, String email, String ipAddress) {
    }
}
