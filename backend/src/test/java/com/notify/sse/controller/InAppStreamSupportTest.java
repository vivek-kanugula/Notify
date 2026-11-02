package com.notify.sse.controller;

import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursor validation and recipient authority (/ , conditions C4 and the approved recipient rule).
 *
 * <p>The cursor assertions are the interesting ones: an invalid cursor must raise the <b>existing</b>
 * {@code VALIDATION_FAILED} contract — not a new error code, and above all not a silent zero, which would replay a
 * client's entire inbox and look like a correctness bug rather than a rejected request.
 */
class InAppStreamSupportTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a malformed, negative or empty cursor is 422 VALIDATION_FAILED, never a silent zero")
    void invalidCursorsAreRejected() {
        for (String bad : new String[]{"abc", "-1", "", "  ", "1.5", "9223372036854775808", "0x10"}) {
            assertThatThrownBy(() -> InAppStreamSupport.parseCursor(bad, "lastEventId"))
                    .as("cursor %s", bad)
                    .isInstanceOf(NotifyException.class)
                    .satisfies(e -> assertThat(((NotifyException) e).getErrorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("a valid cursor parses exactly, including zero and the maximum")
    void validCursorsParse() {
        assertThat(InAppStreamSupport.parseCursor("0", "after")).isZero();
        assertThat(InAppStreamSupport.parseCursor("42", "after")).isEqualTo(42L);
        assertThat(InAppStreamSupport.parseCursor(" 7 ", "after")).isEqualTo(7L);
        assertThat(InAppStreamSupport.parseCursor(Long.toString(Long.MAX_VALUE), "after"))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("recipientRef is required and bounded by the column width")
    void recipientRefIsValidated() {
        assertThatThrownBy(() -> InAppStreamSupport.requireRecipientRef(null))
                .isInstanceOf(NotifyException.class);
        assertThatThrownBy(() -> InAppStreamSupport.requireRecipientRef("   "))
                .isInstanceOf(NotifyException.class);
        assertThatThrownBy(() -> InAppStreamSupport.requireRecipientRef("x".repeat(256)))
                .isInstanceOf(NotifyException.class);

        // Opaque: no normalisation, no case folding, no email shape required.
        assertThat(InAppStreamSupport.requireRecipientRef(" User-42 ")).isEqualTo(" User-42 ");
        assertThat(InAppStreamSupport.requireRecipientRef("not-an-email")).isEqualTo("not-an-email");
    }

    @Test
    @DisplayName("a JWT principal may act for any recipient of its own tenant; an API key needs inapp:stream")
    void recipientAuthorityIsTenantScoped() {
        UUID tenantId = UUID.randomUUID();

        TenantContext.set(TenantPrincipal.forUser(tenantId, UUID.randomUUID(), Set.of()));
        assertThat(InAppStreamSupport.requireStreamAuthority().tenantId()).isEqualTo(tenantId);

        TenantContext.set(TenantPrincipal.forApiKey(tenantId, UUID.randomUUID(), Set.of("notifications:send")));
        assertThatThrownBy(InAppStreamSupport::requireStreamAuthority)
                .isInstanceOf(NotifyException.class)
                .satisfies(e -> assertThat(((NotifyException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_SCOPE));

        TenantContext.set(TenantPrincipal.forApiKey(tenantId, UUID.randomUUID(), Set.of("inapp:stream")));
        assertThat(InAppStreamSupport.requireStreamAuthority().tenantId()).isEqualTo(tenantId);
    }
}
