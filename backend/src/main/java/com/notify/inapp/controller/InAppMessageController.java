package com.notify.inapp.controller;

import com.notify.common.context.TenantPrincipal;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.inapp.config.InAppProperties;
import com.notify.inapp.dto.InAppMessagePageResponse;
import com.notify.inapp.dto.InAppMessageResponse;
import com.notify.inapp.model.InAppMessageView;
import com.notify.inapp.service.InAppMessageService;
import com.notify.sse.controller.InAppStreamSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The saved inbox (/ , ).
 *
 * <p><b>Always available.</b> This is an ordinary authenticated read: no stream token, no lease, no admission
 * barrier and no Redis dependency. The ≈95 s wait the user accepted applies to opening a <em>live stream</em>, never
 * to reading what has already been stored — which is why the inbox stays usable throughout a Redis outage or a
 * quarantine window.
 *
 * <p>Pagination is keyset on {@code seq} (newest first), so a concurrent insert cannot shift a page. An invalid
 * cursor is {@code 422 VALIDATION_FAILED} through the existing contract; no new error code is introduced
 * (condition C4).
 */
@RestController
@RequestMapping("/api/v1")
public class InAppMessageController {

    private final InAppMessageService messages;
    private final InAppProperties props;

    public InAppMessageController(InAppMessageService messages, InAppProperties props) {
        this.messages = messages;
        this.props = props;
    }

    @GetMapping("/inapp-messages")
    public ResponseEntity<InAppMessagePageResponse> list(
            @RequestParam("recipientRef") String recipientRef,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        TenantPrincipal principal = InAppStreamSupport.requireStreamAuthority();
        String recipient = InAppStreamSupport.requireRecipientRef(recipientRef);
        int pageSize = pageSize(size);
        Long cursor = after == null || after.isBlank() ? null : InAppStreamSupport.parseCursor(after, "after");

        List<InAppMessageView> rows = messages.page(principal.tenantId(), recipient, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<InAppMessageView> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? Long.toString(page.get(page.size() - 1).seq()) : null;

        return ResponseEntity.ok(new InAppMessagePageResponse(
                page.stream().map(InAppMessageResponse::from).toList(),
                new InAppMessagePageResponse.PageMeta(page.size(), hasMore),
                nextCursor,
                messages.countUnread(principal.tenantId(), recipient)));
    }

    /** Marks one message read. A message of another tenant or another recipient is 404, never 403. */
    @PostMapping("/inapp-messages/{seq}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable("seq") String seq,
            @RequestParam("recipientRef") String recipientRef
    ) {
        TenantPrincipal principal = InAppStreamSupport.requireStreamAuthority();
        String recipient = InAppStreamSupport.requireRecipientRef(recipientRef);
        long cursor = InAppStreamSupport.parseCursor(seq, "seq");
        if (!messages.markRead(principal.tenantId(), recipient, cursor)) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "No such in-app message");
        }
        return ResponseEntity.noContent().build();
    }

    private int pageSize(Integer requested) {
        if (requested == null) {
            return props.pageSize();
        }
        if (requested < 1 || requested > props.maxPageSize()) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "size must be between 1 and " + props.maxPageSize());
        }
        return requested;
    }
}
