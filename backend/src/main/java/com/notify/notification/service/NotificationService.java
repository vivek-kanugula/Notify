package com.notify.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.config.MailProperties;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.*;
import com.notify.idempotency.model.LockResult;
import com.notify.idempotency.model.ReplayResult;
import com.notify.idempotency.service.IdempotencyService;
import com.notify.idempotency.util.CanonicalHashCalculator;
import com.notify.kafka.config.KafkaTopicsConfig;
import com.notify.dlq.dto.DlqHistoryView;
import com.notify.dlq.dto.DlqNotificationSnapshot;
import com.notify.notification.dto.*;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.event.NotificationTransitionedEvent;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import com.notify.observability.metrics.NotifyMetrics;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.model.RateLimitResult;
import com.notify.ratelimit.service.RateLimitService;
import com.notify.template.dto.ResolvedTemplateSnapshot;
import com.notify.template.service.TemplateService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?:\\.[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)*\\.[A-Za-z]{2,}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotificationRepository notificationRepository;
    private final NotificationStatusHistoryRepository statusHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TenantRepository tenantRepository;
    private final TemplateService templateService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final NotificationTransitioner notificationTransitioner;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final com.notify.quartz.service.NotificationSchedulerService notificationSchedulerService;
    private final MailProperties mailProperties;
    private final NotifyMetrics notifyMetrics;
    private final com.notify.tenant.repository.TenantUserRepository tenantUserRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationStatusHistoryRepository statusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            TenantRepository tenantRepository,
            TemplateService templateService,
            IdempotencyService idempotencyService,
            RateLimitService rateLimitService,
            NotificationTransitioner notificationTransitioner,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this(
                notificationRepository,
                statusHistoryRepository,
                outboxEventRepository,
                tenantRepository,
                templateService,
                idempotencyService,
                rateLimitService,
                notificationTransitioner,
                eventPublisher,
                clock,
                transactionManager,
                null,
                null,
                null,
                null
        );
    }

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationStatusHistoryRepository statusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            TenantRepository tenantRepository,
            TemplateService templateService,
            IdempotencyService idempotencyService,
            RateLimitService rateLimitService,
            NotificationTransitioner notificationTransitioner,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            PlatformTransactionManager transactionManager,
            com.notify.quartz.service.NotificationSchedulerService notificationSchedulerService,
            MailProperties mailProperties
    ) {
        this(
                notificationRepository,
                statusHistoryRepository,
                outboxEventRepository,
                tenantRepository,
                templateService,
                idempotencyService,
                rateLimitService,
                notificationTransitioner,
                eventPublisher,
                clock,
                transactionManager,
                notificationSchedulerService,
                mailProperties,
                null,
                null
        );
    }

    @Autowired
    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationStatusHistoryRepository statusHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            TenantRepository tenantRepository,
            TemplateService templateService,
            IdempotencyService idempotencyService,
            RateLimitService rateLimitService,
            NotificationTransitioner notificationTransitioner,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) com.notify.quartz.service.NotificationSchedulerService notificationSchedulerService,
            @Autowired(required = false) MailProperties mailProperties,
            @Autowired(required = false) NotifyMetrics notifyMetrics,
            @Autowired(required = false) com.notify.tenant.repository.TenantUserRepository tenantUserRepository
    ) {
        this.notificationRepository = notificationRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.tenantRepository = tenantRepository;
        this.templateService = templateService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
        this.notificationTransitioner = notificationTransitioner;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.notificationSchedulerService = notificationSchedulerService;
        this.mailProperties = mailProperties;
        this.notifyMetrics = notifyMetrics;
        this.tenantUserRepository = tenantUserRepository;
    }

    /**
     * Entry point for notification acceptance.
     * Enforces tenant-status, evaluates replay detection BEFORE rate limiting,
     * bypasses token deduction on verified replay (), validates input, and persists atomically.
     */
    public AcceptanceResult acceptNotification(
            UUID tenantId,
            CreateNotificationRequest request,
            String idempotencyKeyHeader
    ) {
        // 1. Authoritative tenant status check
        checkTenantAccess(tenantId, true);

        // 2. Resolve Idempotency Key (header wins over body)
        String idempotencyKey = null;
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            idempotencyKey = idempotencyKeyHeader.trim();
        } else if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            idempotencyKey = request.idempotencyKey().trim();
        }

        if (idempotencyKey != null && idempotencyKey.length() > 128) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Idempotency key cannot exceed 128 characters");
        }

        // 3. Compute Canonical Request Hash
        String incomingHash = idempotencyKey != null ? CanonicalHashCalculator.computeHash(request) : null;

        // 4. Replay Detection (Fast-path Redis + DB fallback) BEFORE Rate-Limit Deduction
        if (idempotencyKey != null) {
            Optional<ReplayResult> replayOpt = idempotencyService.checkReplay(tenantId, idempotencyKey, incomingHash);
            if (replayOpt.isPresent()) {
                ReplayResult replay = replayOpt.get();
                log.info("Idempotent replay served for tenant {} key {} notification {}", tenantId, idempotencyKey, replay.notificationId());
                // Verified matching replay bypasses token deduction per 
                return new AcceptanceResult(replay.notificationId(), replay.acceptanceStatus(), true, null);
            }
        }

        // 5. In-flight Lock Acquisition (handles case where DONE entry appeared after checkReplay)
        String lockToken = null;
        if (idempotencyKey != null) {
            LockResult lockResult = idempotencyService.acquireInFlightLock(tenantId, idempotencyKey, incomingHash);
            if (lockResult instanceof LockResult.Replay replay) {
                log.info("Idempotent replay served from in-flight check for tenant {} key {} notification {}",
                        tenantId, idempotencyKey, replay.result().notificationId());
                return new AcceptanceResult(replay.result().notificationId(), replay.result().acceptanceStatus(), true, null);
            } else if (lockResult instanceof LockResult.Acquired acquired) {
                lockToken = acquired.token();
            }
        }

        // 6. Rate-Limit Deduction (for NEW requests)
        RateLimitResult rateLimitResult = null;
        try {
            rateLimitResult = rateLimitService.consumeToken(tenantId);
        } catch (RateLimitException rle) {
            if (idempotencyKey != null && lockToken != null) {
                idempotencyService.evictInFlightLock(tenantId, idempotencyKey, lockToken);
            }
            throw rle;
        }

        try {
            // 7. Request Validation & Content Snapshot Rendering
            validateRequest(request);

            ResolvedTemplateSnapshot snapshot = null;
            String renderedSubject = request.subject();
            String renderedBody = request.body();

            if (request.templateId() != null) {
                snapshot = templateService.resolveAndRender(
                        tenantId, request.templateId(), request.templateVersion(), request.templateVariables()
                );
                renderedSubject = snapshot.renderedSubject();
                renderedBody = snapshot.renderedBody();
            }

            // 8. Atomic Database Persistence
            return persistNotification(tenantId, request, idempotencyKey, incomingHash, snapshot, renderedSubject, renderedBody, rateLimitResult, lockToken);
        } catch (Throwable t) {
            // Rollback or validation/rendering failure: evict in-flight lock with matching token
            if (idempotencyKey != null && lockToken != null) {
                idempotencyService.evictInFlightLock(tenantId, idempotencyKey, lockToken);
            }
            if (t instanceof DataIntegrityViolationException ex
                    && idempotencyKey != null && containsIdempotencyConstraint(ex)) {
                return handleConcurrentConflict(tenantId, idempotencyKey, incomingHash, rateLimitResult);
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(t);
        }
    }

    protected AcceptanceResult persistNotification(
            UUID tenantId,
            CreateNotificationRequest request,
            String idempotencyKey,
            String incomingHash,
            ResolvedTemplateSnapshot snapshot,
            String renderedSubject,
            String renderedBody,
            RateLimitResult rateLimitResult,
            String lockToken
    ) {
        return transactionTemplate.execute(txStatus -> {
            Instant now = Instant.now(clock);
            NotificationStatus initialStatus = request.scheduledAt() != null
                    ? NotificationStatus.SCHEDULED
                    : NotificationStatus.QUEUED;
            int dispatchGeneration = (initialStatus == NotificationStatus.QUEUED) ? 1 : 0;

            String traceContext = resolveTraceContext();

            NotificationEntity notification = new NotificationEntity();
            notification.setTenantId(tenantId);
            notification.setChannel(request.channel());
            notification.setStatus(initialStatus);
            notification.setRecipient(request.recipient().trim());
            notification.setSubject(renderedSubject != null ? renderedSubject.trim() : null);
            notification.setBody(renderedBody);
            notification.setTemplateVersionId(snapshot != null ? snapshot.templateVersionId() : null);
            notification.setTemplateVariables(toJson(request.templateVariables()));

            Map<String, Object> metaMap = request.metadata() != null
                    ? new java.util.LinkedHashMap<>(request.metadata())
                    : new java.util.LinkedHashMap<>();
            if (request.channel() == Channel.EMAIL) {
                if (request.replyTo() != null && !request.replyTo().isBlank()) {
                    metaMap.put("replyTo", request.replyTo().trim());
                } else if (tenantUserRepository != null) {
                    try {
                        tenantUserRepository.findFirstByTenantIdAndRole(tenantId, com.notify.common.model.Role.TENANT_OWNER)
                                .ifPresent(owner -> metaMap.put("replyTo", owner.getEmail()));
                    } catch (Exception e) {
                        log.debug("Tenant owner lookup for reply-to skipped: {}", e.getMessage());
                    }
                }
            }
            notification.setMetadata(toJson(metaMap));
            notification.setTraceContext(traceContext);
            if (request.channel() == Channel.EMAIL && mailProperties != null) {
                // server-selected sender snapshot; client input and metadata never choose it
                notification.setSender(mailProperties.from());
            }
            notification.setIdempotencyKey(idempotencyKey);
            notification.setRequestHash(incomingHash);
            notification.setScheduledAt(request.scheduledAt());
            notification.setQueuedAt(initialStatus == NotificationStatus.QUEUED ? now : null);
            notification.setDispatchGeneration(dispatchGeneration);
            notification.setDeliveryCycle(1);
            notification.setCreatedAt(now);
            notification.setUpdatedAt(now);

            notification = notificationRepository.save(notification);

            // Record initial status history entry (immutable source of original acceptance status)
            NotificationStatusHistoryEntity history = new NotificationStatusHistoryEntity();
            history.setTenantId(tenantId);
            history.setNotificationId(notification.getId());
            history.setFromStatus(NotificationStatus.CREATED);
            history.setToStatus(initialStatus);
            history.setActor(Actor.SYSTEM);
            history.setReason(initialStatus == NotificationStatus.SCHEDULED ? "Notification scheduled" : "Notification queued for delivery");
            history.setOccurredAt(now);
            statusHistoryRepository.save(history);

            // If QUEUED, persist transactional outbox row; if SCHEDULED, schedule with Quartz
            if (initialStatus == NotificationStatus.QUEUED) {
                OutboxEventEntity outbox = new OutboxEventEntity();
                outbox.setTenantId(tenantId);
                outbox.setAggregateType("NOTIFICATION");
                outbox.setAggregateId(notification.getId());
                outbox.setEventType("NOTIFICATION_QUEUED");
                outbox.setTopic(KafkaTopicsConfig.resolveDispatchTopic(request.channel()));
                outbox.setMessageKey(notification.getId().toString());
                outbox.setPayload(buildOutboxPayload(notification, "NOTIFICATION_QUEUED", now));
                outbox.setTraceContext(traceContext);
                outbox.setStatus(OutboxStatus.PENDING);
                outbox.setAttempts(0);
                outbox.setCreatedAt(now);
                outbox.setNextAttemptAt(now);
                outboxEventRepository.save(outbox);
            } else if (initialStatus == NotificationStatus.SCHEDULED && notificationSchedulerService != null) {
                notificationSchedulerService.scheduleNotificationRelease(
                        tenantId, notification.getId(), request.scheduledAt(), dispatchGeneration, traceContext
                );
            }

            UUID notificationId = notification.getId();
            NotificationStatus committedStatus = initialStatus;

            // Publish event within the transaction: transactional listeners observe once after commit, none on rollback
            eventPublisher.publishEvent(new NotificationTransitionedEvent(
                    tenantId, notificationId, NotificationStatus.CREATED, committedStatus, Actor.SYSTEM, dispatchGeneration, now
            ));

            // Post-commit synchronization
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                String finalLockToken = lockToken;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        if (idempotencyKey != null) {
                            idempotencyService.recordSuccess(tenantId, idempotencyKey, incomingHash, notificationId, committedStatus);
                        }
                        if (notifyMetrics != null) {
                            String priority = "NORMAL";
                            if (request.metadata() != null && request.metadata().containsKey("priority")) {
                                Object p = request.metadata().get("priority");
                                if (p != null) priority = p.toString();
                            }
                            notifyMetrics.incrementCreated(request.channel(), priority, tenantId);
                        }
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED && idempotencyKey != null && finalLockToken != null) {
                            idempotencyService.evictInFlightLock(tenantId, idempotencyKey, finalLockToken);
                        }
                    }
                });
            } else {
                if (idempotencyKey != null) {
                    idempotencyService.recordSuccess(tenantId, idempotencyKey, incomingHash, notificationId, committedStatus);
                }
                if (notifyMetrics != null) {
                    String priority = "NORMAL";
                    if (request.metadata() != null && request.metadata().containsKey("priority")) {
                        Object p = request.metadata().get("priority");
                        if (p != null) priority = p.toString();
                    }
                    notifyMetrics.incrementCreated(request.channel(), priority, tenantId);
                }
            }

            return new AcceptanceResult(notification.getId(), initialStatus, false, rateLimitResult);
        });
    }

    public AcceptanceResult handleConcurrentConflict(
            UUID tenantId,
            String idempotencyKey,
            String incomingHash,
            RateLimitResult rateLimitResult
    ) {
        return transactionTemplate.execute(txStatus -> {
            NotificationEntity existing = notificationRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                    .orElseThrow(() -> new NotifyException(ErrorCode.INTERNAL_ERROR, "Idempotency conflict resolution failed"));

            if (existing.getRequestHash() != null && existing.getRequestHash().equals(incomingHash)) {
                // Verified matching replay! Net tenant token deduction must be 0 per .
                // Refund token only if a token was actually deducted in Redis during this attempt.
                if (rateLimitResult != null && rateLimitResult.tokenDeducted()) {
                    rateLimitService.refundToken(tenantId);
                }

                NotificationStatus originalStatus = statusHistoryRepository
                        .findFirstByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, existing.getId())
                        .map(NotificationStatusHistoryEntity::getToStatus)
                        .orElse(existing.getScheduledAt() != null ? NotificationStatus.SCHEDULED : NotificationStatus.QUEUED);

                // Backfill Redis cache for acceleration
                idempotencyService.recordSuccess(tenantId, idempotencyKey, incomingHash, existing.getId(), originalStatus);

                return new AcceptanceResult(existing.getId(), originalStatus, true, rateLimitResult);
            } else {
                throw new NotifyException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key '" + idempotencyKey + "' was already used with a different request payload");
            }
        });
    }

    /**
     * Conditional tenant-actor cancellation.
     * Validated and executed atomically via NotificationTransitioner.
     * Enforces cancel_reason length <= 64, actor matrix, expected status and generation.
     */
    @Transactional
    public CancelNotificationResponse cancelNotification(UUID tenantId, UUID notificationId, String reason) {
        checkTenantAccess(tenantId, true);

        if (reason != null && reason.length() > 64) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Cancel reason cannot exceed 64 characters");
        }

        String cancelReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Cancelled by tenant";

        NotificationEntity notification = notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Notification not found: " + notificationId));

        notificationTransitioner.cancel(
                tenantId,
                notificationId,
                notification.getStatus(),
                Actor.TENANT,
                notification.getDispatchGeneration(),
                cancelReason
        );

        if (notificationSchedulerService != null) {
            notificationSchedulerService.cancelScheduledRelease(notificationId);
            notificationSchedulerService.cancelRetryTrigger(notificationId);
        }

        return new CancelNotificationResponse(notificationId, NotificationStatus.CANCELLED);
    }

    @Transactional(readOnly = true)
    public NotificationDetailResponse getNotification(UUID tenantId, UUID id) {
        checkTenantAccess(tenantId, false);

        NotificationEntity n = notificationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Notification not found: " + id));

        return new NotificationDetailResponse(
                n.getId(),
                n.getTenantId(),
                n.getChannel(),
                n.getStatus(),
                n.getRecipient(),
                n.getSubject(),
                n.getBody(),
                n.getTemplateVersionId(),
                fromJsonMap(n.getTemplateVariables()),
                fromJsonMap(n.getMetadata()),
                n.getIdempotencyKey(),
                n.getScheduledAt(),
                n.getQueuedAt(),
                n.getProcessingStartedAt(),
                n.getCompletedAt(),
                n.getAttemptCount(),
                n.getMaxAttempts(),
                n.getLastErrorCode(),
                n.getProvider(),
                n.getDispatchGeneration(),
                n.getDeliveryCycle(),
                n.getCreatedAt(),
                n.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> listNotifications(
            UUID tenantId,
            Channel channel,
            NotificationStatus status,
            Pageable pageable
    ) {
        checkTenantAccess(tenantId, false);

        if (channel != null && status != null) {
            return notificationRepository.findSummariesByTenantIdAndChannelAndStatus(tenantId, channel, status, pageable);
        } else if (channel != null) {
            return notificationRepository.findSummariesByTenantIdAndChannel(tenantId, channel, pageable);
        } else if (status != null) {
            return notificationRepository.findSummariesByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            return notificationRepository.findSummariesByTenantId(tenantId, pageable);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationHistoryResponse> getNotificationHistory(UUID tenantId, UUID notificationId) {
        checkTenantAccess(tenantId, false);

        if (!notificationRepository.existsByIdAndTenantId(notificationId, tenantId)) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Notification not found: " + notificationId);
        }

        return statusHistoryRepository.findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, notificationId)
                .stream()
                .map(h -> new NotificationHistoryResponse(
                        h.getId(),
                        h.getFromStatus(),
                        h.getToStatus(),
                        h.getReason(),
                        h.getActor(),
                        h.getOccurredAt()
                ))
                .toList();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void checkTenantAccess(UUID tenantId, boolean isWrite) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found: " + tenantId));

        if (tenant.getStatus() == TenantStatus.REVOKED) {
            throw new NotifyException(ErrorCode.TENANT_REVOKED, "Tenant account has been revoked. Contact support.");
        }

        if (isWrite && tenant.getStatus() == TenantStatus.SUSPENDED) {
            throw new NotifyException(ErrorCode.TENANT_SUSPENDED, "Tenant account is suspended");
        }
    }

    private boolean containsIdempotencyConstraint(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("uq_notifications_tenant_idempotency")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void validateRequest(CreateNotificationRequest request) {
        if (request.recipient() == null || request.recipient().isBlank()) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Recipient cannot be blank");
        }
        if (request.recipient().trim().length() > 255) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Recipient cannot exceed 255 characters");
        }

        if (request.channel() == Channel.EMAIL) {
            if (!EMAIL_PATTERN.matcher(request.recipient().trim()).matches()) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Invalid email address format for recipient");
            }
            if (request.templateId() == null && (request.subject() == null || request.subject().isBlank())) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Subject is required for EMAIL notifications");
            }
        }

        if (request.channel() == Channel.IN_APP) {
            // / (): recipient_ref is an opaque tenant-scoped string compared byte for byte - never
            // normalised, never case-folded, and deliberately NOT email-validated. Only the column width applies.
            if (request.recipient().trim().length() > 255) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                        "Recipient reference must be at most 255 characters for IN_APP notifications");
            }
        }

        if (request.templateId() == null && (request.body() == null || request.body().isBlank())) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Body is required when templateId is not specified");
        }

        if (request.scheduledAt() != null) {
            Instant now = Instant.now(clock);
            if (!request.scheduledAt().isAfter(now)) {
                throw new NotifyException(ErrorCode.VALIDATION_FAILED, "scheduledAt must be in the future");
            }
            if (request.scheduledAt().isAfter(now.plus(90, ChronoUnit.DAYS))) {
                throw new NotifyException(ErrorCode.SCHEDULE_OUT_OF_RANGE, "Scheduled notifications cannot exceed 90 days into the future");
            }
        }
    }

    private String buildOutboxPayload(NotificationEntity notification, String eventType, Instant now) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schemaVersion", 1);
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", eventType);
            envelope.put("notificationId", notification.getId().toString());
            envelope.put("tenantId", notification.getTenantId().toString());
            envelope.put("channel", notification.getChannel().name());
            envelope.put("generation", notification.getDispatchGeneration());
            envelope.put("attempt", Math.max(1, notification.getAttemptCount() + 1));
            envelope.put("occurredAt", now.toString());
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private String resolveTraceContext() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null && attrs.getRequest() != null) {
                String header = attrs.getRequest().getHeader("traceparent");
                if (header != null && isValidTraceparent(header.trim())) {
                    return header.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isValidTraceparent(String traceparent) {
        var matcher = TRACEPARENT_PATTERN.matcher(traceparent.toLowerCase());
        if (!matcher.matches()) {
            return false;
        }
        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        return !traceId.equals("00000000000000000000000000000000") && !spanId.equals("0000000000000000");
    }

    @Transactional
    public boolean releaseScheduledNotification(UUID tenantId, UUID notificationId, int expectedGeneration) {
        Instant now = Instant.now(clock);
        boolean released = notificationTransitioner.releaseScheduled(
                tenantId, notificationId, expectedGeneration, "Scheduled release execution"
        );
        if (!released) {
            log.info("releaseScheduledNotification no-op: notification {} tenant {} expectedGen {} was not in SCHEDULED state, not due, or generation changed",
                    notificationId, tenantId, expectedGeneration);
            return false;
        }

        NotificationEntity notification = notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new IllegalStateException("Notification missing after successful conditional update: " + notificationId));

        OutboxEventEntity outbox = new OutboxEventEntity();
        outbox.setTenantId(tenantId);
        outbox.setAggregateType("NOTIFICATION");
        outbox.setAggregateId(notificationId);
        outbox.setEventType("NOTIFICATION_QUEUED");
        outbox.setTopic(KafkaTopicsConfig.resolveDispatchTopic(notification.getChannel()));
        outbox.setMessageKey(notificationId.toString());
        outbox.setPayload(buildOutboxPayload(notification, "NOTIFICATION_QUEUED", now));
        String traceContext = notification.getTraceContext() != null ? notification.getTraceContext() : resolveTraceContext();
        outbox.setTraceContext(traceContext);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setCreatedAt(now);
        outbox.setNextAttemptAt(now);
        outboxEventRepository.save(outbox);

        return true;
    }

    /**
     * Candidates for stuck-work recovery: notifications in PROCESSING since before {@code cutoff} (/ , ).
     *
     * <p>This is the notification feature's boundary for the recovery scan. The reaper lives in the delivery feature
     * and must not touch this feature's repository or entities, so the scan is exposed here and returns
     * {@link StuckClaimCandidate} DTOs. Read-only and keyset-paged on {@code (processing_started_at, id)}; it
     * decides nothing, because every value it returns is re-tested inside the recovery predicates.
     *
     * @param cursorAt pass {@code null} for the first page, otherwise the previous page's last candidate
     */
    @Transactional(readOnly = true)
    public List<StuckClaimCandidate> findStuckProcessingCandidates(
            Instant cutoff, Instant cursorAt, UUID cursorId, int batchSize
    ) {
        List<NotificationEntity> rows = cursorAt == null
                ? notificationRepository.findStuckProcessingFirstBatch(cutoff, PageRequest.of(0, batchSize))
                : notificationRepository.findStuckProcessingAfterCursor(
                        cutoff, cursorAt, cursorId, PageRequest.of(0, batchSize));
        return rows.stream()
                .map(row -> new StuckClaimCandidate(
                        row.getTenantId(), row.getId(), row.getChannel(), row.getDispatchGeneration(),
                        row.getClaimSeq(), row.getAttemptCount(), row.getMaxAttempts(),
                        row.getProcessingStartedAt()))
                .toList();
    }

    /**
     * Recovers one stuck PROCESSING claim, in one transaction (/ , ).
     *
     * <p>Delegates the decision to {@link NotificationTransitioner#reapStuckProcessing}, whose two conditional
     * updates test the attempt budget as part of their predicates, so the choice between rescheduling and failing is
     * atomic with the transition. On the exhausted path the durable dead-letter event is written <b>here</b>, inside
     * the same transaction, so the FAILED status, its history row and the event commit or roll back together -
     * {@link #recordDeadLetter} is {@code MANDATORY} precisely so it cannot be called any other way.
     *
     * <p>On the rescheduled path nothing else is published: the row is RETRYING and due now, and the existing
     * {@code RetryDueNotificationJob} plus {@code releaseRetryConditional} carry it to QUEUED with the generation
     * bump and the fresh dispatch event, exactly as an ordinary retry does.
     */
    @Transactional
    public NotificationTransitioner.ReapOutcome recoverStuckProcessing(
            UUID tenantId, UUID notificationId, int expectedGeneration, int claimSeq, Instant cutoff
    ) {
        NotificationTransitioner.ReapOutcome outcome = notificationTransitioner.reapStuckProcessing(
                tenantId, notificationId, expectedGeneration, claimSeq, cutoff,
                com.notify.delivery.recovery.StuckProcessingReaper.WORKER_LOST);
        if (outcome != NotificationTransitioner.ReapOutcome.EXHAUSTED) {
            return outcome;
        }

        NotificationEntity failed = notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Notification missing after a successful reap: " + notificationId));
        // MAX_RETRIES_EXCEEDED is literally what happened - the budget is spent. No new DlqReason, and no attempt
        // row: no provider outcome was ever observed, and AttemptOutcome has no value for "unknown".
        recordDeadLetter(
                tenantId, notificationId, failed.getChannel(), failed.getDispatchGeneration(),
                failed.getDeliveryCycle(), failed.getAttemptCount(), DlqReason.MAX_RETRIES_EXCEEDED,
                com.notify.delivery.recovery.StuckProcessingReaper.WORKER_LOST,
                failed.getTraceContext(), Instant.now(clock));
        return outcome;
    }

    @Transactional
    public boolean releaseRetryNotification(UUID tenantId, UUID notificationId, int expectedGeneration) {
        Instant now = Instant.now(clock);
        boolean released = notificationTransitioner.releaseRetry(
                tenantId, notificationId, expectedGeneration, "Retry due release execution"
        );
        if (!released) {
            log.info("releaseRetryNotification no-op: notification {} tenant {} expectedGen {} not RETRYING or not due yet",
                    notificationId, tenantId, expectedGeneration);
            return false;
        }

        NotificationEntity notification = notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new IllegalStateException("Notification missing after successful conditional update: " + notificationId));

        OutboxEventEntity outbox = new OutboxEventEntity();
        outbox.setTenantId(tenantId);
        outbox.setAggregateType("NOTIFICATION");
        outbox.setAggregateId(notificationId);
        outbox.setEventType("NOTIFICATION_RETRY_DUE");
        outbox.setTopic(KafkaTopicsConfig.resolveDispatchTopic(notification.getChannel()));
        outbox.setMessageKey(notificationId.toString());
        outbox.setPayload(buildOutboxPayload(notification, "NOTIFICATION_RETRY_DUE", now));
        String traceContext = notification.getTraceContext() != null ? notification.getTraceContext() : resolveTraceContext();
        outbox.setTraceContext(traceContext);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setCreatedAt(now);
        outbox.setNextAttemptAt(now);
        outboxEventRepository.save(outbox);

        return true;
    }

    /**
     * One consistent single-row observation used by the delivery worker for unclaimed-record disposition and
     * ambiguous-commit reconciliation (plan sections 6.2 and 7). Tenant-scoped, read-only, bounded to 3 s.
     */
    @Transactional(readOnly = true, timeout = 3)
    public Optional<NotificationEvidence> getClaimEvidence(UUID tenantId, UUID notificationId) {
        return notificationRepository.findEvidence(notificationId, tenantId);
    }

    /**
     * Owning tenant of one notification, without a tenant scope (/ ).
     *
     * <p>Used only by the demo webhook receiver, which is given a delivery id and must find out whose signing secret
     * to verify against before it can trust anything else. It returns an identifier and nothing else: no recipient,
     * no subject, no body. Every tenant-facing read stays tenant-scoped.
     */
    @Transactional(readOnly = true, timeout = 3)
    public Optional<UUID> findOwningTenantId(UUID notificationId) {
        return notificationRepository.findById(notificationId).map(NotificationEntity::getTenantId);
    }

    /**
     * Writes the durable dead-letter outbox event for a FAILED notification into {@code notify.dlq.v1}
     * (CONTRACTS envelope). MANDATORY: always joins the result transaction, so the event commits or rolls back
     * atomically with the FAILED status. The DLQ consumer and {@code dead_letter_entries} are .
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDeadLetter(
            UUID tenantId,
            UUID notificationId,
            Channel channel,
            int generation,
            int deliveryCycle,
            int attemptCount,
            DlqReason reason,
            String lastErrorCode,
            String traceContext,
            Instant occurredAt
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "NOTIFICATION_DEAD_LETTERED");
        envelope.put("notificationId", notificationId.toString());
        envelope.put("tenantId", tenantId.toString());
        envelope.put("channel", channel.name());
        envelope.put("generation", generation);
        envelope.put("deliveryCycle", deliveryCycle);
        envelope.put("reason", reason.name());
        envelope.put("lastErrorCode", lastErrorCode);
        envelope.put("attemptCount", attemptCount);
        envelope.put("occurredAt", occurredAt.toString());

        OutboxEventEntity outbox = new OutboxEventEntity();
        outbox.setTenantId(tenantId);
        outbox.setAggregateType("NOTIFICATION");
        outbox.setAggregateId(notificationId);
        outbox.setEventType("NOTIFICATION_DEAD_LETTERED");
        outbox.setTopic(KafkaTopicsConfig.TOPIC_DLQ);
        outbox.setMessageKey(notificationId.toString());
        try {
            outbox.setPayload(MAPPER.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize dead-letter outbox payload", e);
        }
        outbox.setTraceContext(traceContext);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setCreatedAt(occurredAt);
        outbox.setNextAttemptAt(occurredAt);
        outboxEventRepository.save(outbox);
    }

    public record AcceptanceResult(
            UUID notificationId,
            NotificationStatus status,
            boolean isReplay,
            RateLimitResult rateLimitResult
    ) {}

    // ── / (): the service boundary the DLQ feature uses ───────────────────────────────────────────────

    /**
     * The locked notification state a dead-letter materialization is classified against (, plan 2.2.1).
     *
     * <p>{@code PESSIMISTIC_WRITE}, taken before any write, so the consumer never performs a tentative lifecycle
     * update and then rolls it back - a rollback would discard the quarantine row that explains the inconsistency.
     * Returns a DTO: the DLQ feature never receives a notification entity.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeadLetterDecisionView> lockForDeadLetterDecision(UUID notificationId) {
        return notificationRepository.findByIdForUpdate(notificationId)
                .map(n -> new DeadLetterDecisionView(
                        n.getId(), n.getTenantId(), n.getChannel(), n.getStatus(),
                        n.getDeliveryCycle(), n.getDispatchGeneration(), n.getAttemptCount(), n.getMaxAttempts()));
    }

    /**
     * FAILED to DLQ for one authoritative dead-letter event, with its history row ().
     *
     * <p>MANDATORY: it joins the consumer transaction, so the transition, the entry write the caller performs and
     * the history row commit or roll back together, and the Kafka offset is acknowledged only after that commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean materializeDeadLetter(
            UUID tenantId, UUID notificationId, int eventCycle, int eventGeneration, DlqReason reason
    ) {
        return notificationTransitioner.materializeDeadLetter(
                tenantId, notificationId, eventCycle, eventGeneration, reason.name());
    }

    /**
     * Admin replay: DLQ to QUEUED with a new cycle and generation, plus the dispatch outbox event ().
     *
     * <p>MANDATORY, so it joins the admin transaction with the entry transition, the history row and the audit
     * row. The outbox event reuses the <b>existing</b> {@code NOTIFICATION_REPLAYED} type, which all three
     * dispatch models already accept, so no event contract changes.
     *
     * @return the new cycle and generation, or empty when the conditional transition updated no row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReplayDispatch> replayFromDlq(UUID tenantId, UUID notificationId, int expectedGeneration) {
        Instant now = Instant.now(clock);
        boolean replayed = notificationTransitioner.replayFromDlq(
                tenantId, notificationId, expectedGeneration, "Admin DLQ replay");
        if (!replayed) {
            return Optional.empty();
        }
        NotificationEntity notification = notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Notification missing after a successful replay: " + notificationId));

        OutboxEventEntity outbox = new OutboxEventEntity();
        outbox.setTenantId(tenantId);
        outbox.setAggregateType("NOTIFICATION");
        outbox.setAggregateId(notificationId);
        outbox.setEventType("NOTIFICATION_REPLAYED");
        outbox.setTopic(KafkaTopicsConfig.resolveDispatchTopic(notification.getChannel()));
        outbox.setMessageKey(notificationId.toString());
        outbox.setPayload(buildOutboxPayload(notification, "NOTIFICATION_REPLAYED", now));
        outbox.setTraceContext(notification.getTraceContext() != null
                ? notification.getTraceContext() : resolveTraceContext());
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setCreatedAt(now);
        outbox.setNextAttemptAt(now);
        outboxEventRepository.save(outbox);

        return Optional.of(new ReplayDispatch(
                notification.getDeliveryCycle(), notification.getDispatchGeneration(), notification.getChannel()));
    }

    /** What a replay produced, for the admin response and the audit detail. */
    public record ReplayDispatch(int deliveryCycle, int dispatchGeneration, Channel channel) {
    }

    /**
     * Status history for one notification, as the safe admin projection (/ , , ).
     *
     * <p>Newest first, mapped to DTOs: the DLQ feature never receives a history entity, and the reason column is
     * already bounded to 64 characters by the schema.
     */
    @Transactional(readOnly = true)
    public List<DlqHistoryView> historyFor(UUID tenantId, UUID notificationId) {
        return statusHistoryRepository
                .findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, notificationId).stream()
                .sorted(java.util.Comparator
                        .comparing(NotificationStatusHistoryEntity::getOccurredAt).reversed())
                .map(h -> new DlqHistoryView(
                        h.getFromStatus() == null ? null : h.getFromStatus().name(),
                        h.getToStatus() == null ? null : h.getToStatus().name(),
                        h.getActor() == null ? null : h.getActor().name(),
                        h.getReason(), h.getOccurredAt()))
                .toList();
    }

    /** The notification state alone, for a quarantine detail whose association resolved. */
    @Transactional(readOnly = true)
    public Optional<DlqNotificationSnapshot> findNotificationSnapshot(UUID tenantId, UUID notificationId) {
        return notificationRepository.findByIdAndTenantId(notificationId, tenantId)
                .map(n -> new DlqNotificationSnapshot(
                        n.getStatus(), n.getChannel(), n.getDeliveryCycle(), n.getDispatchGeneration(),
                        n.getAttemptCount(), n.getMaxAttempts()));
    }
}
