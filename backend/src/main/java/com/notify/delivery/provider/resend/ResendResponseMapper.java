package com.notify.delivery.provider.resend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.delivery.model.DeliveryResult;

import java.util.regex.Pattern;

/**
 * Pure mapping from a Resend HTTP response to a {@link DeliveryResult} (plan section 10.2, ).
 * Status and error names were checked against Resend's official send-email and error documentation.
 *
 * <p>Stored text is bounded (message 1024, snippet 1024 of the 4096 allowed) and has email addresses masked.
 */
public final class ResendResponseMapper {

    static final int MAX_MESSAGE = 1024;
    static final int MAX_SNIPPET = 1024;
    static final int MAX_MESSAGE_ID = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+");

    private ResendResponseMapper() {
    }

    /**
     * @param status      HTTP status code
     * @param body        response body text, possibly truncated at the byte bound; may be null
     * @param oversize    true when the body exceeded the configured byte bound (the text is then incomplete)
     */
    public static DeliveryResult map(int status, String body, boolean oversize) {
        if (status >= 200 && status < 300) {
            return mapSuccessStatus(status, body, oversize);
        }

        JsonNode json = oversize ? null : parse(body);
        String name = text(json, "name");
        String message = mask(text(json, "message"));
        String snippet = snippet(body);
        String detail = message != null ? limit(message, MAX_MESSAGE) : "Resend responded with HTTP " + status;

        if (status == 408) {
            return retryable("PROVIDER_TIMEOUT", detail, status, snippet);
        }
        if (status == 429) {
            // Rate, daily and monthly quota limits are all retryable (confirmed decision 4); exhaustion reaches the DLQ.
            return retryable("RATE_LIMITED", detail, status, snippet);
        }
        if (status >= 500) {
            return retryable("PROVIDER_UNAVAILABLE", detail, status, snippet);
        }
        if (status == 409) {
            if ("concurrent_idempotent_requests".equals(name)) {
                return retryable("CONCURRENT_IDEMPOTENCY_IN_PROGRESS", detail, status, snippet);
            }
            if ("resource_locked".equals(name)) {
                return retryable("RESOURCE_LOCKED", detail, status, snippet);
            }
            if ("invalid_idempotent_request".equals(name)) {
                return permanent("IDEMPOTENCY_PAYLOAD_MISMATCH", detail, status, snippet);
            }
            return permanent("PROVIDER_CLIENT_ERROR", detail, status, snippet);
        }
        if ("invalid_idempotency_key".equals(name)) {
            return permanent("INVALID_IDEMPOTENCY_KEY", detail, status, snippet);
        }
        // Matched before the generic 401/403 row: an unverified sending domain or a recipient restriction.
        if (status == 403 && "validation_error".equals(name)) {
            return permanent("SENDER_DOMAIN_UNVERIFIED", detail, status, snippet);
        }
        if (status == 401 || status == 403) {
            return permanent("AUTHENTICATION_FAILED", detail, status, snippet);
        }
        if (status == 400 || status == 422) {
            return permanent("VALIDATION_FAILED", detail, status, snippet);
        }
        // Other 4xx, and unexpected 1xx/3xx (redirects are never followed): retrying cannot help.
        return permanent("PROVIDER_CLIENT_ERROR", detail, status, snippet);
    }

    private static DeliveryResult mapSuccessStatus(int status, String body, boolean oversize) {
        // A 2xx whose effect we cannot confirm may still have been accepted: retry with the same idempotency key.
        if (oversize) {
            return retryable("INVALID_PROVIDER_RESPONSE", "Provider response exceeded the size bound", status, null);
        }
        JsonNode json = parse(body);
        String id = text(json, "id");
        if (id == null || id.isBlank()) {
            return retryable("INVALID_PROVIDER_RESPONSE", "Provider response had no message id", status, snippet(body));
        }
        return new DeliveryResult.Success(limit(id.trim(), MAX_MESSAGE_ID));
    }

    private static DeliveryResult retryable(String code, String message, int status, String snippet) {
        return new DeliveryResult.RetryableFailure(code, message, status, snippet);
    }

    private static DeliveryResult permanent(String code, String message, int status, String snippet) {
        return new DeliveryResult.PermanentFailure(code, message, status, snippet);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode json, String field) {
        if (json == null) {
            return null;
        }
        JsonNode value = json.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** Replaces every email address with a fixed token. */
    static String mask(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("[masked-email]");
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return limit(mask(body), MAX_SNIPPET);
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
