package com.notify.observability.logging;

/**
 * PII masking helpers for structured logs (/ ). Email local-parts are reduced to their first
 * character; notification bodies, headers and secrets are never logged at all.
 */
public final class LogMasking {

    private LogMasking() {
    }

    /** {@code john@acme.com} -> {@code j***@acme.com}; non-emails and blanks become {@code ***}. */
    public static String maskEmail(String value) {
        if (value == null || value.isBlank()) {
            return "***";
        }
        int at = value.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }
}
