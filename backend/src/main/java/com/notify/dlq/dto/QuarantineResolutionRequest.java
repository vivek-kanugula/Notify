package com.notify.dlq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Resolving a quarantined record (): reviewed and closed, with a **required** note.
 *
 * <p>There is deliberately no quarantine replay: a record with no verified association cannot be replayed without
 * inventing a target, and a verified one is replayed through its notification entry when there is one.
 */
public record QuarantineResolutionRequest(@NotBlank @Size(min = 1, max = 1024) String note) {
}
