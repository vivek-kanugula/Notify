package com.notify.dlq.consumer;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Refuses to consume a partition that has no committed position (/ , , ; , ).
 *
 * <p><b>Why this is checked at assignment and not left to the poll.</b> With {@code auto.offset.reset=none} an
 * absent position does make the poll throw, and spring-kafka treats that one exception as fatal to the container.
 * But the evidence then comes only from the Kafka exception, the stop is not recorded by
 * {@link DlqConsumerContainerController}, and it is not counted - while an <em>out-of-range</em> position behaves
 * differently again. Checking at assignment makes every unresolved position produce the <b>same</b> stop, the same
 * counter and the same message naming the topic, the partition and the group.
 *
 * <p>It reads committed offsets and <b>nothing else</b>: no seek, no commit, no offset repair. A missing position
 * is never interpreted as "a new partition, so start somewhere" - that interpretation is exactly what 
 * forbids, because a genuinely new partition and a partition whose commit was lost look identical here.
 */
public final class DlqPositionGate implements ConsumerAwareRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(DlqPositionGate.class);

    private final DlqConsumerContainerController controller;
    private final String groupId;

    public DlqPositionGate(DlqConsumerContainerController controller, String groupId) {
        this.controller = controller;
        this.groupId = groupId;
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> committed;
        try {
            committed = consumer.committed(Set.copyOf(partitions));
        } catch (RuntimeException e) {
            controller.stop("UNRESOLVED_OFFSET_POSITION: the committed positions of " + partitions + " in group "
                    + groupId + " could not be read (" + e.getClass().getSimpleName() + "); no seek and no commit "
                    + "was performed");
            return;
        }

        Set<String> withoutPosition = new TreeSet<>();
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata offset = committed.get(partition);
            if (offset == null) {
                withoutPosition.add(partition.toString());
            }
        }
        if (withoutPosition.isEmpty()) {
            log.info("Dead-letter listener {} resumes committed positions for {} in group {}",
                    controller.listenerId(), partitions, groupId);
            return;
        }
        controller.stop("UNRESOLVED_OFFSET_POSITION: no committed position for " + List.copyOf(withoutPosition)
                + " in group " + groupId + "; no seek and no commit was performed, initialize the position "
                + "explicitly");
    }
}
