package com.notify.dlq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.dlq.consumer.OffsetInitializationInput.PartitionOffset;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a reviewed offset-initialization input to a consumer group (/ , , ).
 *
 * <p><b>One explicitly invoked operation, not a discovery subsystem.</b> It runs only when an input file is
 * configured, and it validates everything before it writes anything.
 *
 * <p><b>The race protection, and exactly what establishes it.</b> Three separate things, deliberately not
 * conflated:
 * <ul>
 *   <li><b>Executed in this project (listener side):</b> the listeners are created with {@code autoStartup=false}
 *       and are started only after this completes, so no listener of the target group is running while the write
 *       happens - covered by {@code DlqStartupRefusalContextTest} and {@code DlqConsumerAckOrderIntegrationTest};</li>
 *   <li><b>Executed in this project (precheck):</b> {@link #requireInactiveGroup} refuses before writing anything
 *       when the group has members - covered by {@code DlqOffsetInitializerTest.refusesAnActiveGroup};</li>
 *   <li><b>Executed against a real broker:</b> {@code DlqOffsetAlterationRaceTest} starts an actual group member,
 *       bypasses the precheck and calls {@code Admin.alterConsumerGroupOffsets} directly, asserting the
 *       coordinator rejects it and the committed offset is unchanged. The observed error and the broker and client
 *       versions are recorded in that test and in STATE.md.</li>
 * </ul>
 * Client API inspection alone ({@code javap} on {@code kafka-clients 4.1.2} for
 * {@code Admin.alterConsumerGroupOffsets}, {@code Admin.describeConsumerGroups} and {@code ConsumerGroupState})
 * proves only that the calls exist; it is not evidence of server behaviour and is not relied on as such here.
 * The {@code listConsumerGroupOffsets} read exists <b>only</b> to satisfy the preserve-existing-offsets rule; it is
 * not the race protection.
 *
 * <p><b>It can never overwrite a valid position.</b> A partition that already holds a committed offset is skipped
 * and logged. An out-of-range committed offset is <b>not</b> repaired here: that needs separately authorized
 * recovery, and the listener stays stopped until then.
 *
 * <p><b>Idempotent and resumable.</b> Commits are per partition, so a crash leaves committed partitions committed;
 * re-running the same input skips them and completes the rest. No state is kept outside Kafka - the committed
 * offsets are themselves the record that initialization happened.
 */
public class DlqOffsetInitializer {

    private static final Logger log = LoggerFactory.getLogger(DlqOffsetInitializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What one initialization run did, for the startup gate and for the tests. */
    public record Result(int committed, int skippedExisting, List<String> notes) {
    }

    /**
     * Refusal. A validation refusal means nothing was written; a refusal raised while committing means the
     * partitions already committed stay committed and the same input resumes the rest (see {@link #apply}).
     */
    public static class InitializationRefusedException extends RuntimeException {

        public InitializationRefusedException(String message) {
            super(message);
        }
    }

    private final Admin admin;
    private final String environment;

    public DlqOffsetInitializer(Admin admin, String environment) {
        this.admin = admin;
        this.environment = environment;
    }

    public OffsetInitializationInput read(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file), OffsetInitializationInput.class);
        } catch (Exception e) {
            throw new InitializationRefusedException(
                    "offset initialization input " + file + " could not be read: " + e.getClass().getName());
        }
    }

    /**
     * Validates and applies the input for one group.
     *
     * <p><b>What a refusal means, precisely.</b> Every <em>validation</em> refusal happens before any write, so
     * nothing was committed: environment and group mismatch, a malformed or duplicate coordinate, a topic outside
     * the listener's subscription, an active group, a partition that does not exist, and a requested offset outside
     * the retained range for a partition that actually needs initialization. A failure <em>during</em>
     * {@link #commitPerPartition} is different: the partitions committed before it stay committed, which is the
     * intended design, and re-running the same reviewed input skips them and finishes the rest.
     *
     * <p><b>Order matters.</b> Committed offsets are read <em>before</em> the retained-range check so that the
     * range is checked only for the partitions that will actually be written. A partition that already holds a
     * committed position is skipped untouched and its recorded {@code nextOffset} is never validated - otherwise a
     * retry after retention moved past an already-initialized offset would refuse the whole run and leave the
     * partitions that really need initialization unwritten.
     *
     * @param allowedTopics the topics the target listener actually subscribes to, derived from configuration
     */
    public Result apply(OffsetInitializationInput input, String expectedGroupId, Set<String> allowedTopics) {
        requireMatching(input, expectedGroupId);
        requireInactiveGroup(expectedGroupId);

        Map<TopicPartition, PartitionOffset> wanted = new LinkedHashMap<>();
        for (PartitionOffset entry : input.offsets()) {
            if (entry.topic() == null || entry.topic().isBlank() || entry.partition() < 0 || entry.nextOffset() < 0) {
                throw new InitializationRefusedException("invalid entry in the offset initialization input");
            }
            if (allowedTopics != null && !allowedTopics.contains(entry.topic())) {
                // Derived from the listener's own configuration, so there is no second allowlist to maintain.
                throw new InitializationRefusedException("topic " + entry.topic()
                        + " is not subscribed by the listener of group " + expectedGroupId + " " + allowedTopics);
            }
            TopicPartition partition = new TopicPartition(entry.topic(), entry.partition());
            if (wanted.put(partition, entry) != null) {
                // Including an identical repeat: reviewed data must say each position once, so a conflicting or
                // duplicated edit is reported rather than silently resolved to whichever entry came last.
                throw new InitializationRefusedException(
                        "the offset initialization input lists " + partition + " more than once");
            }
        }
        if (wanted.isEmpty()) {
            throw new InitializationRefusedException("the offset initialization input lists no partitions");
        }

        Map<TopicPartition, OffsetAndMetadata> existing = committedOffsets(expectedGroupId, wanted.keySet());
        Map<TopicPartition, PartitionOffset> toInitialize = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<TopicPartition, PartitionOffset> entry : wanted.entrySet()) {
            OffsetAndMetadata current = existing.get(entry.getKey());
            if (current != null) {
                skipped++;
                notes.add(entry.getKey() + " already committed at " + current.offset() + ", left untouched");
                log.info("Offset initialization skips {}: a committed offset ({}) already exists",
                        entry.getKey(), current.offset());
                continue;
            }
            toInitialize.put(entry.getKey(), entry.getValue());
        }

        if (toInitialize.isEmpty()) {
            log.info("Offset initialization for group {} committed nothing: every listed partition already had a "
                    + "committed position", expectedGroupId);
            return new Result(0, skipped, notes);
        }

        // Only the partitions that will be written. An existing position is never validated here: it is preserved
        // as it stands, and an invalid one is stopped at assignment or on the poll, where recovery is authorized.
        requireInsideRetainedRange(toInitialize);

        Map<TopicPartition, OffsetAndMetadata> toCommit = new LinkedHashMap<>();
        toInitialize.forEach((partition, entry) ->
                toCommit.put(partition, new OffsetAndMetadata(entry.nextOffset())));
        commitPerPartition(expectedGroupId, toCommit, notes);
        log.info("Offset initialization for group {} committed {} partitions, skipped {} with existing positions",
                expectedGroupId, toCommit.size(), skipped);
        return new Result(toCommit.size(), skipped, notes);
    }

    private void requireMatching(OffsetInitializationInput input, String expectedGroupId) {
        if (input.environment() == null || !input.environment().equals(environment)) {
            throw new InitializationRefusedException("input environment " + input.environment()
                    + " does not match the running environment " + environment);
        }
        if (input.groupId() == null || !input.groupId().equals(expectedGroupId)) {
            throw new InitializationRefusedException(
                    "input groupId " + input.groupId() + " does not match the configured group " + expectedGroupId);
        }
        if (input.offsets() == null) {
            throw new InitializationRefusedException("the offset initialization input has no offsets list");
        }
    }

    /**
     * The group must be inactive. Asserted explicitly so the refusal is actionable, on top of the coordinator
     * rejecting an alteration for an active group anyway.
     */
    private void requireInactiveGroup(String groupId) {
        try {
            ConsumerGroupDescription description =
                    admin.describeConsumerGroups(List.of(groupId)).all().get().get(groupId);
            if (description == null) {
                return;
            }
            ConsumerGroupState state = description.state();
            boolean inactive = state == ConsumerGroupState.EMPTY || state == ConsumerGroupState.DEAD
                    || state == ConsumerGroupState.UNKNOWN;
            if (!inactive || !description.members().isEmpty()) {
                throw new InitializationRefusedException("consumer group " + groupId + " is " + state + " with "
                        + description.members().size() + " members; initialization requires an inactive group");
            }
        } catch (InitializationRefusedException e) {
            throw e;
        } catch (Exception e) {
            throw new InitializationRefusedException(
                    "could not describe consumer group " + groupId + ": " + e.getClass().getName());
        }
    }

    private void requireInsideRetainedRange(Map<TopicPartition, PartitionOffset> wanted) {
        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        wanted.keySet().forEach(partition -> {
            earliest.put(partition, OffsetSpec.earliest());
            latest.put(partition, OffsetSpec.latest());
        });
        try {
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> low =
                    admin.listOffsets(earliest).all().get();
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> high =
                    admin.listOffsets(latest).all().get();
            for (Map.Entry<TopicPartition, PartitionOffset> entry : wanted.entrySet()) {
                ListOffsetsResult.ListOffsetsResultInfo lowInfo = low.get(entry.getKey());
                ListOffsetsResult.ListOffsetsResultInfo highInfo = high.get(entry.getKey());
                if (lowInfo == null || highInfo == null) {
                    throw new InitializationRefusedException(
                            "topic or partition " + entry.getKey() + " does not exist");
                }
                long next = entry.getValue().nextOffset();
                if (next < lowInfo.offset() || next > highInfo.offset()) {
                    throw new InitializationRefusedException("offset " + next + " for " + entry.getKey()
                            + " is outside the retained range [" + lowInfo.offset() + ", " + highInfo.offset() + "]");
                }
            }
        } catch (InitializationRefusedException e) {
            throw e;
        } catch (Exception e) {
            throw new InitializationRefusedException("could not read partition ranges: " + e.getClass().getName());
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets(String groupId, Set<TopicPartition> partitions) {
        try {
            Map<TopicPartition, OffsetAndMetadata> all =
                    admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
            Map<TopicPartition, OffsetAndMetadata> relevant = new HashMap<>();
            all.forEach((partition, offset) -> {
                if (offset != null && partitions.contains(partition)) {
                    relevant.put(partition, offset);
                }
            });
            return relevant;
        } catch (Exception e) {
            throw new InitializationRefusedException(
                    "could not read committed offsets for group " + groupId + ": " + e.getClass().getName());
        }
    }

    /**
     * Per partition, so a crash or a mid-run rejection leaves a partial but valid result that the same input can
     * resume. This is the one phase where a refusal does <b>not</b> mean "nothing was written".
     */
    private void commitPerPartition(
            String groupId, Map<TopicPartition, OffsetAndMetadata> toCommit, List<String> notes) {
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : toCommit.entrySet()) {
            try {
                admin.alterConsumerGroupOffsets(groupId, Map.of(entry.getKey(), entry.getValue())).all().get();
                notes.add(entry.getKey() + " initialized to " + entry.getValue().offset());
            } catch (Exception e) {
                throw new InitializationRefusedException("could not initialize " + entry.getKey() + ": "
                        + e.getClass().getName() + "; partitions committed before this one stay committed and the"
                        + " same input resumes the rest");
            }
        }
    }
}
