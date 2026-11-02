package com.notify.dlq.dto;

import java.util.List;

/**
 * A keyset page (). {@code nextCursor} is null on the last page; a client never computes an offset.
 */
public record DlqPage<T>(List<T> items, String nextCursor) {
}
