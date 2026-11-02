package com.notify.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Request to create a new API key.
 */
public record CreateApiKeyRequest(

    @NotBlank(message = "name is required")
    @Size(max = 128, message = "name must be at most 128 characters")
    String name,

    @NotEmpty(message = "at least one scope is required")
    List<@NotBlank String> scopes,

    /** Optional ISO-8601 expiry. Null means the key never expires. */
    Instant expiresAt
) {}
