package com.notify.dlq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of a replay or discard request ().
 *
 * <p>Two records rather than one with a conditional rule, because the requirement differs: a **discard note is
 * required** () - a discard throws work away and the note is the only record of why - while a replay note is
 * optional.
 */
public final class DlqResolutionRequest {

    private DlqResolutionRequest() {
    }

    public record Replay(@Size(max = 1024) String note) {
    }

    public record Discard(@NotBlank @Size(min = 1, max = 1024) String note) {
    }
}
