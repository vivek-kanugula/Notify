package com.notify.inapp.service;

import com.notify.delivery.provider.inapp.InAppMessageComposer;
import com.notify.notification.dto.ClaimSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The <b>only</b> writer of {@code inapp_messages} (/ , ).
 *
 * <p>Two things happen here and nowhere else, in this order:
 *
 * <ol>
 *   <li><b>the advisory lock.</b> {@code pg_advisory_xact_lock} on a stable 64-bit hash of
 *       {@code (tenant_id, recipient_ref)} is held until the transaction ends, so for one recipient the interval
 *       <em>allocate the sequence value &rarr; commit</em> is serialised. Allocation order therefore equals commit
 *       order, which is the property a cursor over the global {@code seq} needs in order never to step past a row
 *       that is still to commit. Without it, a transaction that allocated a lower {@code seq} could commit after a
 *       higher one and be lost from every stream for good;</li>
 *   <li><b>the insert</b>, with {@code ON CONFLICT (notification_id) DO NOTHING} so a redelivery leaves exactly one
 *       row and still reports success - and, importantly, without aborting the transaction the way a caught
 *       constraint violation would in PostgreSQL.</li>
 * </ol>
 *
 * <p><b>Mandatory persistence (condition C1).</b> After the insert the writer confirms that exactly one row for
 * this {@code notification_id} exists, and throws otherwise. The caller runs inside the result transaction, so a
 * throw rolls the whole delivery back and the notification is never recorded DELIVERED. A silently absent inbox row
 * is therefore impossible.
 *
 * <p>Runs only inside an existing transaction ({@code MANDATORY}): the lock is worthless if it is taken and released
 * in a transaction of its own.
 */
@Component
public class InAppMessageWriter {

    private final JdbcTemplate jdbc;
    private final InAppMessageComposer composer;
    private final Clock clock;

    public InAppMessageWriter(JdbcTemplate jdbc, InAppMessageComposer composer, Clock clock) {
        this.jdbc = jdbc;
        this.composer = composer;
        this.clock = clock;
    }

    /** @return the committed row's {@code seq}, whether this call inserted it or found an earlier identical one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long write(ClaimSnapshot claim) {
        UUID tenantId = claim.tenantId();
        String recipientRef = claim.recipient();
        if (recipientRef == null || recipientRef.isBlank()) {
            // The acceptance path validates this; reaching here means a row was stored that never should have been.
            throw new IllegalStateException("IN_APP notification " + claim.id() + " has no recipient reference");
        }

        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class,
                InAppRecipientLockKey.of(tenantId, recipientRef));

        InAppMessageComposer.Composed composed = composer.compose(claim);
        jdbc.update("""
                INSERT INTO inapp_messages (tenant_id, notification_id, recipient_ref, subject, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (notification_id) DO NOTHING
                """,
                tenantId, claim.id(), recipientRef, composed.subject(), composed.body(),
                Timestamp.from(clock.instant()));

        List<Long> seqs = jdbc.queryForList(
                "SELECT seq FROM inapp_messages WHERE notification_id = ?", Long.class, claim.id());
        if (seqs.size() != 1) {
            // Mandatory persistence: never report DELIVERED without exactly one durable row.
            throw new IllegalStateException("Expected exactly one inapp_messages row for notification "
                    + claim.id() + ", found " + seqs.size());
        }
        return seqs.get(0);
    }
}