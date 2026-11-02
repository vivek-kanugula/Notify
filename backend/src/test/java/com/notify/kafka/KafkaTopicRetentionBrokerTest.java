package com.notify.kafka;

import com.notify.AbstractKafkaIntegrationTest;
import com.notify.kafka.config.KafkaTopicConfigReconciler;
import com.notify.kafka.config.KafkaTopicsConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that KafkaTopicConfigReconciler restores correct broker-side retention.ms
 * on a topic that already exists with an incorrect value.
 *
 * Sequence:
 *  1. Spring context starts; KafkaTopicConfigReconciler.run() creates all topics with correct retention.
 *  2. Test deliberately sets notify.dispatch.email.v1 retention.ms to 86400000 (1 day) via AdminClient.
 *  3. Test reads back the broker config and asserts the wrong value is present (proving arrangement worked).
 *  4. Test calls reconciler.reconcileTopicConfigs(adminClient).
 *  5. Test reads back the broker config and asserts retention.ms is now 259200000 (3 days = contracted value).
 *  6. All other contracted topics are verified for their contracted retention.ms.
 *  7. Broker-side partition counts are asserted for all contracted topics.
 */
@org.springframework.test.annotation.DirtiesContext
class KafkaTopicRetentionBrokerTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private KafkaTopicConfigReconciler reconciler;

    private static final String WRONG_RETENTION = "86400000";   // 1 day — not a contracted value
    private static final String EMAIL_CONTRACTED_RETENTION = "259200000";  // 3 days
    private static final String DLQ_CONTRACTED_RETENTION = "1209600000";   // 14 days

    @org.junit.jupiter.api.BeforeEach
    void setUpTopics() {
        if (reconciler != null) {
            reconciler.stop();
            reconciler.reconcileNow();
        }
    }

    @Test
    @DisplayName("Broker retention: reconciler restores correct retention.ms on a pre-existing topic that has an incorrect broker-side value")
    void brokerTopicRetention_reconcilerRestoresContractedRetention_onPreExistingTopicWithWrongValue() throws Exception {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(props)) {
            String targetTopic = KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL;
            ConfigResource emailResource = new ConfigResource(ConfigResource.Type.TOPIC, targetTopic);

            // Step 1: Corrupt the broker-side retention for the already-existing email topic.
            AlterConfigOp corruptOp = new AlterConfigOp(
                    new ConfigEntry("retention.ms", WRONG_RETENTION),
                    AlterConfigOp.OpType.SET
            );
            adminClient.incrementalAlterConfigs(
                    Map.of(emailResource, (Collection<AlterConfigOp>) List.of(corruptOp))
            ).all().get(10, TimeUnit.SECONDS);

            // Step 2: Read back and assert the wrong value is genuinely present on the broker.
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Config corruptedConfig = adminClient.describeConfigs(List.of(emailResource))
                        .all().get(10, TimeUnit.SECONDS).get(emailResource);
                assertThat(corruptedConfig.get("retention.ms").value())
                        .as("Broker retention.ms must be set to the wrong value before reconciliation")
                        .isEqualTo(WRONG_RETENTION);
            });

            // Step 3: Invoke the reconciler — it must detect the mismatch and call incrementalAlterConfigs.
            reconciler.reconcileTopicConfigs(adminClient);

            // Step 4: Assert the contracted value is restored on the email topic.
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                Config repairedConfig = adminClient.describeConfigs(List.of(emailResource))
                        .all().get(10, TimeUnit.SECONDS).get(emailResource);
                assertThat(repairedConfig.get("retention.ms").value())
                        .as("Reconciler must restore broker retention.ms to 3-day contracted value for " + targetTopic)
                        .isEqualTo(EMAIL_CONTRACTED_RETENTION);
            });

            // Step 5: Verify all contracted topics have correct broker-side retention.ms.
            for (KafkaTopicConfigReconciler.TopicSpec spec : KafkaTopicConfigReconciler.EXPECTED_TOPICS) {
                ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, spec.name());
                Map<ConfigResource, Config> configs = adminClient.describeConfigs(List.of(resource))
                        .all().get(10, TimeUnit.SECONDS);
                Config config = configs.get(resource);

                assertThat(config)
                        .as("Broker must have a config entry for topic " + spec.name())
                        .isNotNull();
                assertThat(config.get("retention.ms").value())
                        .as("Broker retention.ms for topic " + spec.name())
                        .isEqualTo(String.valueOf(spec.retentionMs()));
            }

            // Step 6: Spot-check the two distinct retention buckets explicitly.
            Config emailFinal = adminClient.describeConfigs(List.of(emailResource))
                    .all().get(10, TimeUnit.SECONDS).get(emailResource);
            assertThat(emailFinal.get("retention.ms").value())
                    .as("notify.dispatch.email.v1 must have 3-day retention")
                    .isEqualTo(EMAIL_CONTRACTED_RETENTION);

            ConfigResource dlqResource = new ConfigResource(ConfigResource.Type.TOPIC, KafkaTopicsConfig.TOPIC_DLQ);
            Config dlqConfig = adminClient.describeConfigs(List.of(dlqResource))
                    .all().get(10, TimeUnit.SECONDS).get(dlqResource);
            assertThat(dlqConfig.get("retention.ms").value())
                    .as("notify.dlq.v1 must have 14-day retention")
                    .isEqualTo(DLQ_CONTRACTED_RETENTION);

            // Step 7: Verify broker-side partition counts match CONTRACTS.md
            List<String> topicNames = KafkaTopicConfigReconciler.EXPECTED_TOPICS.stream()
                    .map(KafkaTopicConfigReconciler.TopicSpec::name)
                    .toList();
            var topicDescriptions = adminClient.describeTopics(topicNames).allTopicNames().get(10, TimeUnit.SECONDS);
            for (KafkaTopicConfigReconciler.TopicSpec spec : KafkaTopicConfigReconciler.EXPECTED_TOPICS) {
                var desc = topicDescriptions.get(spec.name());
                assertThat(desc)
                        .as("Topic description must exist for " + spec.name())
                        .isNotNull();
                assertThat(desc.partitions().size())
                        .as("Broker partition count for topic " + spec.name())
                        .isEqualTo(spec.partitions());
            }
        }
    }

    @Test
    @DisplayName("Partition drift: reconciler detects partition drift on existing topics and does NOT mutate broker partition counts")
    void brokerTopicPartitions_driftDetectedWithoutMutation() throws Exception {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());

        String targetTopic = KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK;
        try {
            try (AdminClient adminClient = AdminClient.create(props)) {
                // Delete an expected topic and recreate it with drifted partition count (e.g. 1 partition instead of 6)
                try {
                    adminClient.deleteTopics(List.of(targetTopic)).all().get(10, TimeUnit.SECONDS);
                    org.awaitility.Awaitility.await()
                            .atMost(10, TimeUnit.SECONDS)
                            .pollInterval(100, TimeUnit.MILLISECONDS)
                            .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(targetTopic));
                } catch (Exception ignored) {}

                // Recreate with 1 partition instead of 6 (drift!)
                org.apache.kafka.clients.admin.NewTopic driftedTopic =
                        new org.apache.kafka.clients.admin.NewTopic(targetTopic, 1, (short) 1)
                                .configs(Map.of("retention.ms", EMAIL_CONTRACTED_RETENTION));
                adminClient.createTopics(List.of(driftedTopic)).all().get(10, TimeUnit.SECONDS);
                org.awaitility.Awaitility.await()
                        .atMost(10, TimeUnit.SECONDS)
                        .pollInterval(100, TimeUnit.MILLISECONDS)
                        .until(() -> adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(targetTopic));

                // Verify broker has 1 partition
                var descBefore = adminClient.describeTopics(List.of(targetTopic)).allTopicNames().get(10, TimeUnit.SECONDS).get(targetTopic);
                assertThat(descBefore.partitions()).hasSize(1);

                // Invoke reconciler: it must detect drift, log warning, but NOT mutate broker partition count
                reconciler.reconcileTopicConfigs(adminClient);

                // Verify broker STILL has 1 partition (broker was NOT mutated automatically per CONTRACTS.md)
                var descAfter = adminClient.describeTopics(List.of(targetTopic)).allTopicNames().get(10, TimeUnit.SECONDS).get(targetTopic);
                assertThat(descAfter.partitions()).hasSize(1);
            }
        } finally {
            // Restore contracted 6 partitions on target topic so shared broker remains clean
            try (AdminClient adminClient = AdminClient.create(props)) {
                try {
                    adminClient.deleteTopics(List.of(targetTopic)).all().get(10, TimeUnit.SECONDS);
                    org.awaitility.Awaitility.await()
                            .atMost(10, TimeUnit.SECONDS)
                            .pollInterval(100, TimeUnit.MILLISECONDS)
                            .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(targetTopic));
                } catch (Exception ignored) {}
                adminClient.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(targetTopic, 6, (short) 1)
                        .configs(Map.of("retention.ms", EMAIL_CONTRACTED_RETENTION)))).all().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }
}