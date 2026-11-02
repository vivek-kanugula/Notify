package com.notify.idempotency.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.notification.dto.CreateNotificationRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Deterministic SHA-256 canonical request hash calculator for incoming notification payloads.
 * Serializes the complete approved request-intent descriptor with explicit field boundaries
 * and recursive map key sorting (including maps nested inside arrays/lists).
 * Guarantees that template publication between retries cannot change the computed hash.
 */
public class CanonicalHashCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    public static String computeHash(CreateNotificationRequest request) {
        if (request == null) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Request cannot be null");
        }

        Map<String, Object> descriptor = new LinkedHashMap<>();

        // 1. Channel
        descriptor.put("channel", request.channel() != null ? request.channel().name() : null);

        // 2. Recipient
        descriptor.put("recipient", request.recipient() != null ? request.recipient().trim() : null);

        // 3. Content descriptor (template with explicit or latest version vs inline subject & body)
        Map<String, Object> content = new LinkedHashMap<>();
        if (request.templateId() != null) {
            content.put("type", "TEMPLATE");
            content.put("templateId", request.templateId().toString());
            content.put("version", request.templateVersion() != null
                    ? String.valueOf(request.templateVersion())
                    : "LATEST");
            content.put("variables", normalizeNested(request.templateVariables()));
        } else {
            content.put("type", "INLINE");
            content.put("subject", request.subject() != null ? request.subject().trim() : null);
            content.put("body", request.body());
        }
        descriptor.put("content", content);

        // 4. ScheduledAt (normalized to ISO-8601 UTC)
        if (request.scheduledAt() != null) {
            descriptor.put("scheduledAt", UTC_FORMATTER.format(request.scheduledAt().atOffset(ZoneOffset.UTC)));
        } else {
            descriptor.put("scheduledAt", null);
        }

        // 5. Metadata
        descriptor.put("metadata", normalizeNested(request.metadata()));

        try {
            String json = MAPPER.writeValueAsString(descriptor);
            return sha256Hex(json);
        } catch (JsonProcessingException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "Failed to serialize canonical request descriptor: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeNested(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sorted.put(key, normalizeNested(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalizedList = new ArrayList<>(list.size());
            for (Object item : list) {
                normalizedList.add(normalizeNested(item));
            }
            return normalizedList;
        }
        if (value instanceof Set<?> set) {
            List<Object> normalizedList = new ArrayList<>(set.size());
            for (Object item : set) {
                normalizedList.add(normalizeNested(item));
            }
            normalizedList.sort(Comparator.comparing(String::valueOf));
            return normalizedList;
        }
        return value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }
}
