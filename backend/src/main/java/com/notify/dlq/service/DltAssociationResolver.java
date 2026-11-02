package com.notify.dlq.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.dlq.dto.DispatchEnvelopeView;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Decides what a dead-lettered dispatch record refers to (/ , , ).
 *
 * <p><b>Association, never causation.</b> A resolved association means "this record refers to that dispatch". It
 * never means the record explains why the notification failed, and it never authorizes a lifecycle change - the
 * authoritative reason belongs to the committed delivery or reaper result, and only the `notify.dlq.v1` event can
 * materialize an entry.
 *
 * <p><b>The database is the only authority.</b> The notification is looked up by id alone, through the existing
 * service-boundary method, and the tenant of record comes from that lookup. A payload tenant that disagrees is
 * recorded as a mismatch, never trusted, and never silently adopted.
 */
@Service
public class DltAssociationResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotificationService notificationService;

    public DltAssociationResolver(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** The outcome of resolving one record: the claims it made, and what the database says about them. */
    public record Resolution(
            QuarantineAssociation association,
            UUID claimedNotificationId,
            UUID claimedTenantId,
            Integer claimedGeneration,
            UUID verifiedNotificationId,
            UUID verifiedTenantId
    ) {
        public static Resolution unparsable() {
            return new Resolution(QuarantineAssociation.UNPARSABLE, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Resolution resolve(byte[] recordValue) {
        Optional<DispatchEnvelopeView> parsed = parse(recordValue);
        if (parsed.isEmpty()) {
            return Resolution.unparsable();
        }
        DispatchEnvelopeView claim = parsed.get();

        Optional<UUID> owningTenant = notificationService.findOwningTenantId(claim.claimedNotificationId());
        if (owningTenant.isEmpty()) {
            return new Resolution(QuarantineAssociation.UNKNOWN_NOTIFICATION,
                    claim.claimedNotificationId(), claim.claimedTenantId(), claim.claimedGeneration(), null, null);
        }
        UUID verifiedTenant = owningTenant.get();
        if (claim.claimedTenantId() == null || !verifiedTenant.equals(claim.claimedTenantId())) {
            // The notification exists; the payload names someone else. Record both, trust only the database.
            return new Resolution(QuarantineAssociation.TENANT_MISMATCH,
                    claim.claimedNotificationId(), claim.claimedTenantId(), claim.claimedGeneration(),
                    claim.claimedNotificationId(), verifiedTenant);
        }
        return new Resolution(QuarantineAssociation.RESOLVED,
                claim.claimedNotificationId(), claim.claimedTenantId(), claim.claimedGeneration(),
                claim.claimedNotificationId(), verifiedTenant);
    }

    private Optional<DispatchEnvelopeView> parse(byte[] recordValue) {
        if (recordValue == null || recordValue.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(recordValue);
            return DispatchEnvelopeView.parse(node);
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }
}
