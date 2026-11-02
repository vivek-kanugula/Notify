package com.notify.webhook.security;

/**
 * One destination-policy violation (). The same policy runs at configuration time, where this becomes
 * {@code 422 VALIDATION_FAILED}, and at delivery time, where it becomes a permanent
 * {@code WEBHOOK_DESTINATION_BLOCKED} failure. The message names the rule, never a resolved address's owner.
 */
public class DestinationRejectedException extends RuntimeException {

    /** Policy rule that rejected the destination. Stable enough to assert on in tests and to log. */
    public enum Reason {
        INVALID_SYNTAX,
        SCHEME_NOT_ALLOWED,
        USERINFO_PRESENT,
        FRAGMENT_PRESENT,
        PORT_NOT_ALLOWED,
        URL_TOO_LONG,
        HOST_MISSING,
        HOST_IS_IP_LITERAL,
        HOST_BLOCKED,
        HOST_NOT_ALLOWED_FOR_DEMO,
        DNS_FAILURE,
        ADDRESS_BLOCKED
    }

    private final Reason reason;

    public DestinationRejectedException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
