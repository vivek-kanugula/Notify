package com.notify.dlq.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The reviewed offset-initialization input (/ , , requirement 3 of ).
 *
 * <p><b>Data, reviewed like a migration.</b> Exact integers only: no {@code latest}, no {@code earliest}, no
 * timestamp, no wildcard. If an operator wants "latest" or "as of a timestamp", that selection is resolved
 * <em>once</em>, by a human-run read-only query, into the integers written here - so the durable boundary is this
 * recorded map and never a moving value.
 *
 * <p>{@code environment} and {@code groupId} are part of the input precisely so an input prepared for one place
 * cannot be applied in another: the initializer refuses when either does not match the running configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record OffsetInitializationInput(
        String environment,
        String groupId,
        List<PartitionOffset> offsets
) {

    public record PartitionOffset(String topic, int partition, long nextOffset) {
    }
}
