package com.notify.inapp.dto;

import java.util.List;

/**
 * A keyset page of the inbox (/ , ).
 *
 * <p>Keyset rather than offset: a concurrent insert cannot shift a page, which matters because the inbox is written
 * to while a client is paging through a resync. {@code nextCursor} is the {@code seq} to pass as {@code after} for
 * the following page, or {@code null} at the end.
 */
public record InAppMessagePageResponse(
        List<InAppMessageResponse> content,
        PageMeta page,
        String nextCursor,
        long unread
) {

    public record PageMeta(int size, boolean hasMore) {
    }
}
