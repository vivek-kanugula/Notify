package com.notify.sse.controller;

import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.ApiKeyScope;

/**
 * Shared authorization and input rules for the in-app surface (/ , ).
 *
 * <p><b>Recipient authority.</b> A tenant owner's JWT, or an API key carrying {@code inapp:stream}, may act for
 * <em>any</em> {@code recipientRef} inside its own tenant — and never for another tenant, because the tenant comes
 * from the verified credential and never from the request. That is deliberate tenant-scoped authority and does not
 * establish individual end-recipient authentication: Notify has no end-recipient identity to authenticate.
 *
 * <p><b>Cursors.</b> A malformed or negative cursor is {@code 422 VALIDATION_FAILED} using the existing frozen error
 * set — never a silent zero, which would replay a client's whole inbox, and never a new error code.
 */
public final class InAppStreamSupport {

    public static final int RECIPIENT_REF_MAX = 255;

    private InAppStreamSupport() {
    }

    /** The authenticated tenant, provided it may act on in-app streams. */
    public static TenantPrincipal requireStreamAuthority() {
        TenantPrincipal principal = TenantContext.require();
        if ("API_KEY".equals(principal.authType())
                && !principal.scopes().contains(ApiKeyScope.INAPP_STREAM.getValue())) {
            throw new NotifyException(ErrorCode.INSUFFICIENT_SCOPE,
                    "This API key does not carry the " + ApiKeyScope.INAPP_STREAM.getValue() + " scope");
        }
        return principal;
    }

    public static String requireRecipientRef(String recipientRef) {
        if (recipientRef == null || recipientRef.isBlank()) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "recipientRef is required");
        }
        if (recipientRef.length() > RECIPIENT_REF_MAX) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "recipientRef must be at most " + RECIPIENT_REF_MAX + " characters");
        }
        return recipientRef;
    }

    /** Parses a cursor exactly, or raises the existing validation error (condition C4). */
    public static long parseCursor(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " must be a non-negative integer");
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " must be a non-negative integer");
        }
        if (value < 0) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " must be a non-negative integer");
        }
        return value;
    }
}
