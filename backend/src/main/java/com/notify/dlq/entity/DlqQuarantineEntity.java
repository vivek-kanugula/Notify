package com.notify.dlq.entity;

import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One quarantined record: a dead-lettered dispatch record, or a {@code notify.dlq.v1} record that could not be
 * applied (V7, and , ).
 *
 * <p><b>Why this table exists.</b> {@code dead_letter_entries} requires {@code notification_id NOT NULL} with a
 * composite FK to {@code notifications}, so a record whose payload is unparsable, names an unknown notification, or
 * names a tenant that is not the owner cannot be stored there without inventing an identifier - which is
 * forbidden. And a record whose association <em>is</em> verified still must not become a business entry, because
 * association is not causation ().
 *
 * <p><b>Trust boundary, enforced by the field names.</b> {@code claimedTenantId}, {@code claimedNotificationId}
 * and {@code claimedGeneration} are copied from an untrusted payload for triage only, and are never used for
 * authorization, for filtering, or as the tenant of record. {@code verifiedTenantId} and
 * {@code verifiedNotificationId} are written only from a database lookup.
 *
 * <p><b>No payload is retained</b> - only {@code payloadSha256} and {@code payloadByteCount}. The dispatch envelope
 * carries no body, and a poison payload is arbitrary untrusted input, so this table never becomes a place where
 * such content lands. The dispatch envelope carries no delivery cycle either, so none is recorded or reconstructed.
 */
@Entity
@Table(name = "dlq_quarantine")
public class DlqQuarantineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "partition_number", nullable = false)
    private int partitionNumber;

    /** Named {@code record_offset} because {@code offset} is a reserved word. */
    @Column(name = "record_offset", nullable = false)
    private long recordOffset;

    @Column(name = "dlt_reason", nullable = false, length = 64)
    private String dltReason;

    @Column(name = "error_category", length = 64)
    private String errorCategory;

    @Column(name = "exception_class", length = 255)
    private String exceptionClass;

    @Column(name = "payload_sha256", length = 64)
    private String payloadSha256;

    @Column(name = "payload_byte_count")
    private Integer payloadByteCount;

    @Column(name = "claimed_tenant_id")
    private UUID claimedTenantId;

    @Column(name = "claimed_notification_id")
    private UUID claimedNotificationId;

    @Column(name = "claimed_generation")
    private Integer claimedGeneration;

    @Column(name = "verified_tenant_id")
    private UUID verifiedTenantId;

    @Column(name = "verified_notification_id")
    private UUID verifiedNotificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QuarantineAssociation association;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QuarantineStatus status = QuarantineStatus.OPEN;

    @Column(name = "resolution_note", length = 1024)
    private String resolutionNote;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public DlqQuarantineEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getPartitionNumber() {
        return partitionNumber;
    }

    public void setPartitionNumber(int partitionNumber) {
        this.partitionNumber = partitionNumber;
    }

    public long getRecordOffset() {
        return recordOffset;
    }

    public void setRecordOffset(long recordOffset) {
        this.recordOffset = recordOffset;
    }

    public String getDltReason() {
        return dltReason;
    }

    public void setDltReason(String dltReason) {
        this.dltReason = dltReason;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    public void setErrorCategory(String errorCategory) {
        this.errorCategory = errorCategory;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public void setPayloadSha256(String payloadSha256) {
        this.payloadSha256 = payloadSha256;
    }

    public Integer getPayloadByteCount() {
        return payloadByteCount;
    }

    public void setPayloadByteCount(Integer payloadByteCount) {
        this.payloadByteCount = payloadByteCount;
    }

    public UUID getClaimedTenantId() {
        return claimedTenantId;
    }

    public void setClaimedTenantId(UUID claimedTenantId) {
        this.claimedTenantId = claimedTenantId;
    }

    public UUID getClaimedNotificationId() {
        return claimedNotificationId;
    }

    public void setClaimedNotificationId(UUID claimedNotificationId) {
        this.claimedNotificationId = claimedNotificationId;
    }

    public Integer getClaimedGeneration() {
        return claimedGeneration;
    }

    public void setClaimedGeneration(Integer claimedGeneration) {
        this.claimedGeneration = claimedGeneration;
    }

    public UUID getVerifiedTenantId() {
        return verifiedTenantId;
    }

    public void setVerifiedTenantId(UUID verifiedTenantId) {
        this.verifiedTenantId = verifiedTenantId;
    }

    public UUID getVerifiedNotificationId() {
        return verifiedNotificationId;
    }

    public void setVerifiedNotificationId(UUID verifiedNotificationId) {
        this.verifiedNotificationId = verifiedNotificationId;
    }

    public QuarantineAssociation getAssociation() {
        return association;
    }

    public void setAssociation(QuarantineAssociation association) {
        this.association = association;
    }

    public QuarantineStatus getStatus() {
        return status;
    }

    public void setStatus(QuarantineStatus status) {
        this.status = status;
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

    /** Identifiers only: a quarantine row must never print a payload, a claimed body or a secret. */
    @Override
    public String toString() {
        return "DlqQuarantineEntity[id=" + id + ", topic=" + topic + ", partition=" + partitionNumber
                + ", offset=" + recordOffset + ", association=" + association + ", status=" + status + "]";
    }
}
