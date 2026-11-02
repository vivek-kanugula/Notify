package com.notify.dlq.service;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.dlq.dto.DeadLetterEventEnvelope;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.entity.DlqQuarantineEntity;
import com.notify.dlq.model.MaterializationOutcome;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.repository.DlqQuarantineRepository;
import com.notify.dlq.service.DeadLetterMaterializationService.RecordCoordinates;
import com.notify.dlq.service.DltAssociationResolver.Resolution;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Association is not causation (/ , / ; to , , , ).
 *
 * <p>Every dead-lettered dispatch record is quarantine-only, **including a verified association**: the DLT flow
 * writes no notification status, no business entry, no history row and no outbox event, so it cannot create a
 * replacement business dead-letter event and cannot overwrite the authoritative reason that the committed delivery
 * or reaper result established. `POISON_MESSAGE` therefore gains no producer in .
 */
class PoisonCausationBoundaryTest extends AbstractPostgresIntegrationTest {

    private static final AtomicLong OFFSETS = new AtomicLong();

    @Autowired private DltAssociationResolver resolver;
    @Autowired private QuarantineService quarantine;
    @Autowired private DeadLetterMaterializationService materialization;
    @Autowired private DlqQuarantineRepository quarantineRepository;
    @Autowired private DeadLetterEntryRepository entries;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private UUID tenantId;
    private String fixtureTopic;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("PoisonOrg", "poison-" + UUID.randomUUID())).getId();
        fixtureTopic = "notify.test.dlt." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dlq_quarantine WHERE topic = ?", fixtureTopic);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seed(String status, int cycle, int generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                    dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                VALUES (?, ?, 'EMAIL', ?, 'someone@example.com', 'subject', 'secret-body', ?, ?, 1, 5, now())
                """, id, tenantId, status, generation, cycle);
        return id;
    }

    private RecordCoordinates coordinates(byte[] value) {
        return new RecordCoordinates(fixtureTopic, 0, OFFSETS.incrementAndGet(), value);
    }

    private byte[] dispatchEnvelope(UUID notificationId, UUID payloadTenantId, int generation) {
        String json = """
                {"schemaVersion":1,"eventId":"%s","eventType":"NOTIFICATION_QUEUED","notificationId":"%s",
                 "tenantId":"%s","channel":"EMAIL","generation":%d,"attempt":1,"occurredAt":"%s"}
                """.formatted(UUID.randomUUID(), notificationId, payloadTenantId, generation, clock.instant());
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private DlqQuarantineEntity quarantineRecord(RecordCoordinates coordinates, Resolution resolution) {
        return quarantine.recordDltRecord(coordinates, "POISON_PAYLOAD", "POISON",
                "com.fasterxml.jackson.core.JsonParseException", resolution.association(),
                resolution.claimedTenantId(), resolution.claimedNotificationId(), resolution.claimedGeneration(),
                resolution.verifiedTenantId(), resolution.verifiedNotificationId());
    }

    @Test
    @DisplayName("an unparsable record quarantines as UNPARSABLE and no identifier is invented")
    void unparsableRecord() {
        byte[] garbage = "not json at all".getBytes(StandardCharsets.UTF_8);
        Resolution resolution = resolver.resolve(garbage);

        assertThat(resolution.association()).isEqualTo(QuarantineAssociation.UNPARSABLE);
        assertThat(resolution.claimedNotificationId()).isNull();
        assertThat(resolution.verifiedNotificationId()).isNull();

        DlqQuarantineEntity row = quarantineRecord(coordinates(garbage), resolution);
        assertThat(row.getAssociation()).isEqualTo(QuarantineAssociation.UNPARSABLE);
        assertThat(row.getPayloadByteCount()).isEqualTo(garbage.length);
        assertThat(row.getPayloadSha256()).isEqualTo(QuarantineService.digestOf("not json at all"));
    }

    @Test
    @DisplayName("a record naming an unknown notification quarantines as UNKNOWN_NOTIFICATION")
    void unknownNotification() {
        UUID absent = UUID.randomUUID();
        byte[] value = dispatchEnvelope(absent, tenantId, 0);

        Resolution resolution = resolver.resolve(value);

        assertThat(resolution.association()).isEqualTo(QuarantineAssociation.UNKNOWN_NOTIFICATION);
        assertThat(resolution.claimedNotificationId()).isEqualTo(absent);
        assertThat(resolution.verifiedNotificationId()).as("nothing is verified, and nothing is invented").isNull();
        assertThat(resolution.verifiedTenantId()).isNull();
    }

    @Test
    @DisplayName("a payload tenant that is not the owner is TENANT_MISMATCH, and the database wins")
    void tenantMismatchTrustsTheDatabase() {
        UUID id = seed("FAILED", 1, 0);
        UUID impostor = UUID.randomUUID();
        byte[] value = dispatchEnvelope(id, impostor, 0);

        Resolution resolution = resolver.resolve(value);

        assertThat(resolution.association()).isEqualTo(QuarantineAssociation.TENANT_MISMATCH);
        assertThat(resolution.claimedTenantId()).as("the payload claim is recorded").isEqualTo(impostor);
        assertThat(resolution.verifiedTenantId()).as("and the database is the authority").isEqualTo(tenantId);

        DlqQuarantineEntity row = quarantineRecord(coordinates(value), resolution);
        assertThat(row.getClaimedTenantId()).isEqualTo(impostor);
        assertThat(row.getVerifiedTenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("a redelivered DLT record creates no second quarantine row")
    void redeliveryIsIdempotent() {
        byte[] value = "garbage".getBytes(StandardCharsets.UTF_8);
        RecordCoordinates coordinates = coordinates(value);
        Resolution resolution = resolver.resolve(value);

        DlqQuarantineEntity first = quarantineRecord(coordinates, resolution);
        DlqQuarantineEntity second = quarantineRecord(coordinates, resolution);

        assertThat(second.getId()).as("the record coordinates are its identity").isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlq_quarantine WHERE topic = ?", Long.class,
                fixtureTopic)).isEqualTo(1);
    }

    @Test
    @DisplayName("every verified poison record is quarantine-only, whatever the notification status is")
    void verifiedAssociationNeverMaterializes() {
        for (String status : new String[]{"FAILED", "DLQ", "QUEUED", "PROCESSING", "RETRYING", "DELIVERED",
                "CANCELLED"}) {
            UUID id = seed(status, 1, 0);
            byte[] value = dispatchEnvelope(id, tenantId, 0);
            Resolution resolution = resolver.resolve(value);
            assertThat(resolution.association())
                    .as("status %s: the association resolves", status)
                    .isEqualTo(QuarantineAssociation.RESOLVED);

            quarantineRecord(coordinates(value), resolution);

            assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                    .as("status %s is untouched by the DLT flow", status)
                    .isEqualTo(status);
            assertThat(entries.findByNotificationId(id))
                    .as("status %s produced no business entry, so POISON_MESSAGE has no producer", status)
                    .isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM notification_status_history WHERE notification_id = ?", Long.class, id))
                    .as("status %s produced no history row", status)
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, id))
                    .as("status %s produced no outbox event", status)
                    .isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_entries WHERE tenant_id = ?",
                Long.class, tenantId))
                .as("no POISON_MESSAGE entry exists anywhere for this tenant")
                .isZero();
    }

    @Test
    @DisplayName("no quarantine row records or reconstructs a delivery cycle")
    void noCycleIsAttributedToARecord() {
        UUID id = seed("DELIVERED", 3, 2);
        byte[] value = dispatchEnvelope(id, tenantId, 2);
        Resolution resolution = resolver.resolve(value);

        DlqQuarantineEntity row = quarantineRecord(coordinates(value), resolution);

        assertThat(row.getClaimedGeneration())
                .as("the dispatch envelope carries a generation, so that is recorded as claimed")
                .isEqualTo(2);
        // There is deliberately no cycle column at all: the envelope never carried one, and the notification
        // current cycle (3) must not be presented as the record cycle.
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'dlq_quarantine'", String.class))
                .as("no cycle column exists to tempt a reconstruction")
                .noneMatch(column -> column.contains("cycle"));
    }

    @Test
    @DisplayName("a delayed stuck-processing record cannot terminalize work that moved on")
    void delayedUnresolvedRecordChangesNothing() {
        // The reaper already rescheduled this work, and a later cycle has been delivered.
        UUID id = seed("DELIVERED", 2, 1);
        byte[] value = dispatchEnvelope(id, tenantId, 0);
        Resolution resolution = resolver.resolve(value);

        quarantine.recordDltRecord(coordinates(value), "UNRESOLVED_PROCESSING_STUCK", "UNRESOLVED",
                "com.notify.delivery.worker.exception.WorkerExceptions$UnresolvedProcessingException",
                resolution.association(), resolution.claimedTenantId(), resolution.claimedNotificationId(),
                resolution.claimedGeneration(), resolution.verifiedTenantId(),
                resolution.verifiedNotificationId());

        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .as("a stale observation cannot un-deliver delivered work")
                .isEqualTo("DELIVERED");
        assertThat(entries.findByNotificationId(id)).isEmpty();
    }

    @Test
    @DisplayName("no payload, body or secret is persisted by a quarantine row")
    void noPayloadIsPersisted() {
        UUID id = seed("FAILED", 1, 0);
        byte[] value = dispatchEnvelope(id, tenantId, 0);
        Resolution resolution = resolver.resolve(value);

        DlqQuarantineEntity row = quarantineRecord(coordinates(value), resolution);

        String persisted = jdbc.queryForObject(
                "SELECT dlq_quarantine::text FROM dlq_quarantine WHERE id = ?", String.class, row.getId());
        assertThat(persisted)
                .doesNotContain("secret-body")
                .doesNotContain("someone@example.com")
                .doesNotContain("NOTIFICATION_QUEUED");
        assertThat(row.getPayloadSha256()).hasSize(64);
        assertThat(row.getPayloadByteCount()).isEqualTo(value.length);
    }

    @Test
    @DisplayName("authoritative failure then poison record: the authoritative reason survives")
    void authoritativeFirstThenPoison() {
        UUID id = seed("FAILED", 1, 0);

        // 1. The authoritative event materializes the entry with the committed reason.
        DeadLetterEventEnvelope authoritative = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId, Channel.EMAIL, 0, 1,
                DlqReason.MAX_RETRIES_EXCEEDED, "PROVIDER_UNAVAILABLE", 5, clock.instant().toString());
        assertThat(materialization.apply(authoritative, coordinates("{}".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(MaterializationOutcome.MATERIALIZED);

        // 2. The associated poison record arrives afterwards.
        byte[] value = dispatchEnvelope(id, tenantId, 0);
        quarantineRecord(coordinates(value), resolver.resolve(value));

        assertAuthoritativeReasonSurvived(id);
    }

    @Test
    @DisplayName("poison record then authoritative failure: the authoritative reason still decides")
    void poisonFirstThenAuthoritative() {
        UUID id = seed("FAILED", 1, 0);

        // 1. The poison record arrives first and may only quarantine.
        byte[] value = dispatchEnvelope(id, tenantId, 0);
        quarantineRecord(coordinates(value), resolver.resolve(value));
        assertThat(entries.findByNotificationId(id))
                .as("the DLT path created no entry it could later be blamed for")
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");

        // 2. The authoritative event then materializes normally.
        DeadLetterEventEnvelope authoritative = new DeadLetterEventEnvelope(
                1, UUID.randomUUID(), DeadLetterEventEnvelope.EVENT_TYPE, id, tenantId, Channel.EMAIL, 0, 1,
                DlqReason.MAX_RETRIES_EXCEEDED, "PROVIDER_UNAVAILABLE", 5, clock.instant().toString());
        assertThat(materialization.apply(authoritative, coordinates("{}".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(MaterializationOutcome.MATERIALIZED);

        assertAuthoritativeReasonSurvived(id);
    }

    /** Either order must leave exactly the same state: the committed verdict, plus an inspectable record. */
    private void assertAuthoritativeReasonSurvived(UUID id) {
        DeadLetterEntryEntity entry = entries.findByNotificationId(id).orElseThrow();
        assertThat(entry.getReason())
                .as("the reason is the committed delivery verdict, never POISON_MESSAGE from a transport record")
                .isEqualTo(DlqReason.MAX_RETRIES_EXCEEDED);
        assertThat(entry.getLastErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(entry.getReplayCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_entries WHERE notification_id = ?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_status_history WHERE notification_id = ? AND to_status = 'DLQ'",
                Long.class, id))
                .as("exactly one FAILED to DLQ transition, and the DLT path contributed none of it")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlq_quarantine WHERE topic = ? "
                        + "AND verified_notification_id = ?", Long.class, fixtureTopic, id))
                .as("the quarantine record remains inspectable")
                .isEqualTo(1);
    }
}
