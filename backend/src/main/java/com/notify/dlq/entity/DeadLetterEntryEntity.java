package com.notify.dlq.entity;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dead_letter_entries")
public class DeadLetterEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "notification_id", nullable = false, unique = true)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private DlqReason reason;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_snapshot", columnDefinition = "jsonb")
    private String payloadSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DlqStatus status = DlqStatus.OPEN;

    @Column(name = "replay_count", nullable = false)
    private int replayCount = 0;

    @Column(name = "resolution_note", length = 1024)
    private String resolutionNote;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public DeadLetterEntryEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(UUID notificationId) {
        this.notificationId = notificationId;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public DlqReason getReason() {
        return reason;
    }

    public void setReason(DlqReason reason) {
        this.reason = reason;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getPayloadSnapshot() {
        return payloadSnapshot;
    }

    public void setPayloadSnapshot(String payloadSnapshot) {
        this.payloadSnapshot = payloadSnapshot;
    }

    public DlqStatus getStatus() {
        return status;
    }

    public void setStatus(DlqStatus status) {
        this.status = status;
    }

    public int getReplayCount() {
        return replayCount;
    }

    public void setReplayCount(int replayCount) {
        this.replayCount = replayCount;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * The event this entry last applied (V7, / ).
     *
     * <p>A <b>mutable last-applied marker</b>, not a ledger: it is overwritten on each reopen, and a partial unique
     * index (where not null) makes it impossible for two entries to claim the same event. Duplicate detection uses
     * it; protection against a superseded event comes from the cycle comparison, not from this column.
     */
    @Column(name = "last_event_id")
    private UUID lastEventId;

    /**
     * The delivery cycle of the event that produced the current state, from the dead-letter envelope.
     *
     * <p>{@code null} means <b>unknown, never zero</b>: a legacy or partially written row is never reopened and
     * never treated as duplicate or stale - it is quarantined for inspection (outcome matrix rows 9 and 11).
     */
    @Column(name = "source_delivery_cycle")
    private Integer sourceDeliveryCycle;

    /** The dispatch generation of that event; recorded for diagnosis, not used as a fence by itself. */
    @Column(name = "source_generation")
    private Integer sourceGeneration;

    public UUID getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(UUID lastEventId) {
        this.lastEventId = lastEventId;
    }

    public Integer getSourceDeliveryCycle() {
        return sourceDeliveryCycle;
    }

    public void setSourceDeliveryCycle(Integer sourceDeliveryCycle) {
        this.sourceDeliveryCycle = sourceDeliveryCycle;
    }

    public Integer getSourceGeneration() {
        return sourceGeneration;
    }

    public void setSourceGeneration(Integer sourceGeneration) {
        this.sourceGeneration = sourceGeneration;
    }
}
