package com.notify.dlq.dto;

import com.notify.common.model.DlqStatus;

import java.util.UUID;

/**
 * What a replay produced (/ , , ).
 *
 * <p>{@code possibleDuplicateDelivery} and {@code ambiguityNote} are part of the response on purpose: the operator
 * choosing to replay is the person who needs to know that a replay issues a **new send identity** and can produce
 * a second external effect if an earlier attempt was in fact accepted remotely. No exactly-once delivery is
 * claimed anywhere.
 */
public record DlqReplayResponse(
        UUID entryId,
        UUID notificationId,
        DlqStatus status,
        int replayCount,
        int newDeliveryCycle,
        int newDispatchGeneration,
        boolean possibleDuplicateDelivery,
        String ambiguityNote
) {
}
