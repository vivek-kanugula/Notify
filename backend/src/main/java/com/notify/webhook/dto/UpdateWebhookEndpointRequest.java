package com.notify.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.notify.webhook.security.WebhookUrlPolicy;

/**
 * Upsert of the tenant's webhook endpoint. {@code enabled} is optional: absent leaves the stored value unchanged.
 * The URL's real validation is the destination policy (); the constraints here only keep obvious junk out.
 */
public record UpdateWebhookEndpointRequest(
        @NotBlank(message = "Url is required")
        @Size(max = WebhookUrlPolicy.MAX_URL_LENGTH, message = "Url cannot exceed 2048 characters")
        String url,

        Boolean enabled
) {
}
