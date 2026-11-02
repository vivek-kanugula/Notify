package com.notify.kafka;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.OutboxStatus;
import com.notify.kafka.config.KafkaTopicConfigReconciler;
import com.notify.kafka.config.KafkaTopicsConfig;
import com.notify.kafka.producer.KafkaDispatchProducer;
import com.notify.outbox.entity.OutboxEventEntity;
import com.notify.outbox.repository.OutboxEventRepository;
import com.notify.outbox.service.OutboxRelayService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolated broker test verifying:
 * 1. Broker-side auto-topic-creation is disabled (KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false").
 * 2. Delete-between-check-and-send ordering: send fails with UnknownTopicOrPartitionException without
 *    ever creating a wrong-shaped (1-partition) topic at the broker.
 * 3. Outbox relay records bounded failure and exponential backoff rather than entering a hot retry loop.
 * 4. Background reconciliation restores contracted topic (6 partitions for email/webhook, 3 for in-app, 3-day retention)
 *    and relay resumes publishing.
 * 5. Partition drift is detected and exposes a visible, actionable signal without auto-mutating broker partitions.
 *
 * Uses an isolated KafkaContainer to guarantee the shared development broker is never mutated.
 */
class KafkaPublishingTopicProvisioningRaceTest extends AbstractPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublishingTopicProvisioningRaceTest.class);

    private static final KafkaContainer isolatedBroker;

    static {
        isolatedBroker = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        isolatedBroker.start();
    }

    @DynamicPropertySource
    static void configureIsolatedKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", isolatedBroker::getBootstrapServers);
    }

    @AfterAll
    static void tearDownIsolatedBroker() {
        if (isolatedBroker.isRunning()) {
            isolatedBroker.stop();
        }
    }

    @Autowired
    private KafkaTopicConfigReconciler topicReconciler;

    @Autowired
    private KafkaDispatchProducer dispatchProducer;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OutboxRelayService outboxRelayService;
    private UUID tenantId;

    private AdminClient createAdminClient() {
        Properties props = new Properties();
        props.put("bootstrap.servers", isolatedBroker.getBootstrapServers());
        return AdminClient.create(props);
    }

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        TenantEntity tenant = new TenantEntity("RaceOrg", "race-org-" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        this.tenantId = tenant.getId();
        this.outboxRelayService = new OutboxRelayService(
                outboxEventRepository,
                dispatchProducer,
                Clock.systemUTC(),
                transactionManager
        );
        topicReconciler.start();
    }

    @Test
    @DisplayName("Broker setting: auto-topic-creation is disabled on broker; reject unknown topics without auto-creation")
    void brokerSetting_autoTopicCreationDisabled_rejectsUnknownTopicsWithoutAutoCreation() throws Exception {
        String nonContractTopic = "notify.unknown-probe." + UUID.randomUUID();

        // Attempting to send to a non-existent topic must fail with UnknownTopicOrPartitionException
        var future = dispatchProducer.send(
                nonContractTopic,
                "key-probe",
                "{\"probe\":true}",
                null,
                tenantId
        );

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(UnknownTopicOrPartitionException.class);

        // Verify broker never auto-created the topic with default (1) partition count
        try (AdminClient adminClient = createAdminClient()) {
            Set<String> topics = adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
            assertThat(topics)
                    .as("Broker must not auto-create non-existent topic %s", nonContractTopic)
                    .doesNotContain(nonContractTopic);
        }
    }

    @Test
    @DisplayName("Delete between check and send: send fails without creating wrong-partition topic; reconciler restores contracted partitions and relay resumes")
    void deleteBetweenCheckAndSend_sendFailsWithoutCreatingWrongShapedTopic_reconcilerRestoresAndRelayResumes() throws Exception {
        try (AdminClient adminClient = createAdminClient()) {
            String emailTopic = KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL;
            String inAppTopic = KafkaTopicsConfig.TOPIC_DISPATCH_INAPP;

            // 1. Initial reconciliation provisions email and in-app topics with contracted shapes
            assertThat(topicReconciler.ensureTopicProvisioned(emailTopic)).isTrue();
            assertThat(topicReconciler.ensureTopicProvisioned(inAppTopic)).isTrue();

            TopicDescription emailDesc = adminClient.describeTopics(List.of(emailTopic))
                    .allTopicNames().get(5, TimeUnit.SECONDS).get(emailTopic);
            assertThat(emailDesc.partitions()).hasSize(6);

            // Temporarily pause background recurring reconciler loop to force and observe the delete-between-check-and-send race window
            topicReconciler.stop();

            try {
                // 2. Force delete-between-check-and-send ordering on email topic
                adminClient.deleteTopics(List.of(emailTopic)).all().get(5, TimeUnit.SECONDS);
                org.awaitility.Awaitility.await()
                        .atMost(10, TimeUnit.SECONDS)
                        .pollInterval(200, TimeUnit.MILLISECONDS)
                        .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(emailTopic));

                // 3. Immediately send to deleted topic: send fails cleanly because broker auto-create is OFF
                var future = dispatchProducer.send(
                        emailTopic,
                        "order-race-1",
                        "{\"msg\":\"payload during race\"}",
                        null,
                        tenantId
                );

                assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(Exception.class);

                // 4. Crucial guarantee: broker NEVER created a 1-partition topic!
                Set<String> topicsAfterFailedSend = adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
                assertThat(topicsAfterFailedSend)
                        .as("Broker must NEVER auto-create wrong-shaped 1-partition topic upon send failure")
                        .doesNotContain(emailTopic);

                // 5. Reconciliation restores contracted 6 partitions and 3-day retention
                boolean restored = topicReconciler.reconcileNow();
                assertThat(restored).isTrue();
                assertThat(topicReconciler.isTopicVerified(emailTopic)).isTrue();

                TopicDescription restoredEmailDesc = adminClient.describeTopics(List.of(emailTopic))
                        .allTopicNames().get(5, TimeUnit.SECONDS).get(emailTopic);
                assertThat(restoredEmailDesc.partitions())
                        .as("Restored topic %s must have contracted 6 partitions, not 1", emailTopic)
                        .hasSize(6);

                ConfigResource emailResource = new ConfigResource(ConfigResource.Type.TOPIC, emailTopic);
                Config emailConfig = adminClient.describeConfigs(List.of(emailResource))
                        .all().get(5, TimeUnit.SECONDS).get(emailResource);
                assertThat(emailConfig.get("retention.ms").value())
                        .as("Topic %s must have contracted 3-day retention.ms", emailTopic)
                        .isEqualTo("259200000");

                // 6. Resending succeeds
                var retryFuture = dispatchProducer.send(
                        emailTopic,
                        "order-race-1-retry",
                        "{\"msg\":\"payload after recovery\"}",
                        null,
                        tenantId
                );
                var sendResult = retryFuture.get(5, TimeUnit.SECONDS);
                assertThat(sendResult).isNotNull();
                assertThat(sendResult.getRecordMetadata().topic()).isEqualTo(emailTopic);

                // 7. Verify the same for in-app topic (contracted 3 partitions per CONTRACTS.md)
                adminClient.deleteTopics(List.of(inAppTopic)).all().get(5, TimeUnit.SECONDS);
                org.awaitility.Awaitility.await()
                        .atMost(10, TimeUnit.SECONDS)
                        .pollInterval(200, TimeUnit.MILLISECONDS)
                        .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(inAppTopic));

                var inAppFuture = dispatchProducer.send(
                        inAppTopic,
                        "inapp-race-1",
                        "{\"msg\":\"in-app payload during race\"}",
                        null,
                        tenantId
                );

                assertThatThrownBy(() -> inAppFuture.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(Exception.class);

                assertThat(adminClient.listTopics().names().get(5, TimeUnit.SECONDS))
                        .doesNotContain(inAppTopic);

                assertThat(topicReconciler.reconcileNow()).isTrue();

                TopicDescription restoredInAppDesc = adminClient.describeTopics(List.of(inAppTopic))
                        .allTopicNames().get(5, TimeUnit.SECONDS).get(inAppTopic);
                assertThat(restoredInAppDesc.partitions())
                        .as("Restored topic %s must have contracted 3 partitions", inAppTopic)
                        .hasSize(3);

                ConfigResource inAppResource = new ConfigResource(ConfigResource.Type.TOPIC, inAppTopic);
                Config inAppConfig = adminClient.describeConfigs(List.of(inAppResource))
                        .all().get(5, TimeUnit.SECONDS).get(inAppResource);
                assertThat(inAppConfig.get("retention.ms").value()).isEqualTo("259200000");
            } finally {
                // Resume background reconciler lifecycle
                topicReconciler.start();
            }
        }
    }

    @Test
    @DisplayName("Delete between check and send: outbox relay persists attempts and backoff without entering a hot retry loop; resumes after reconciliation")
    void deleteBetweenCheckAndSend_outboxRelayPersistsBackoff_neverLeavesHotRetryLoop_resumesUponReconciliation() throws Exception {
        try (AdminClient adminClient = createAdminClient()) {
            String emailTopic = KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL;
            topicReconciler.reconcileNow();

            // 1. Force delete topic on broker
            adminClient.deleteTopics(List.of(emailTopic)).all().get(5, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(emailTopic));

            // 2. Create pending outbox event
            Instant baseTime = Instant.parse("2026-10-10T10:00:00Z");
            Clock fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);
            outboxRelayService.setClock(fixedClock);

            OutboxEventEntity event = new OutboxEventEntity();
            event.setTenantId(tenantId);
            event.setAggregateType("NOTIFICATION");
            event.setAggregateId(UUID.randomUUID());
            event.setEventType("NOTIFICATION_QUEUED");
            event.setTopic(emailTopic);
            event.setMessageKey("race-key");
            event.setPayload("{\"schemaVersion\":1}");
            event.setStatus(OutboxStatus.PENDING);
            event.setAttempts(0);
            event.setCreatedAt(baseTime);
            event.setNextAttemptAt(baseTime);
            event = outboxEventRepository.save(event);

            // 3. Run outbox relay batch: send fails with UnknownTopicOrPartitionException
            int published = outboxRelayService.processBatch();
            assertThat(published).isEqualTo(0);

            // 4. Assert row persisted attempts=1 and nextAttemptAt backed off (NOT attempts=0; NOT rolled back!)
            OutboxEventEntity failedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
            assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(failedEvent.getAttempts()).isEqualTo(1);
            assertThat(failedEvent.getNextAttemptAt()).isAfter(baseTime);

            // 5. Assert NOT in hot retry loop: immediate second batch at baseTime finds 0 due rows
            int hotRetryPublished = outboxRelayService.processBatch();
            assertThat(hotRetryPublished).isEqualTo(0);
            OutboxEventEntity untouched = outboxEventRepository.findById(event.getId()).orElseThrow();
            assertThat(untouched.getAttempts()).isEqualTo(1);

            // 6. Background reconciliation restores contracted topic
            topicReconciler.reconcileNow();
            TopicDescription desc = adminClient.describeTopics(List.of(emailTopic))
                    .allTopicNames().get(5, TimeUnit.SECONDS).get(emailTopic);
            assertThat(desc.partitions()).hasSize(6);

            // 7. Advance clock past nextAttemptAt: relay resumes and publishes
            Instant retryTime = failedEvent.getNextAttemptAt().plusSeconds(1);
            outboxRelayService.setClock(Clock.fixed(retryTime, ZoneOffset.UTC));
            int resumedPublished = outboxRelayService.processBatch();
            assertThat(resumedPublished).isEqualTo(1);

            OutboxEventEntity publishedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
            assertThat(publishedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(publishedEvent.getPublishedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("Partition drift: reconciler detects partition drift on existing topics and preserves visible actionable signal without mutating broker")
    void partitionDrift_preservesVisibleActionableSignalWithoutMutatingBroker() throws Exception {
        try (AdminClient adminClient = createAdminClient()) {
            String webhookTopic = KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK;

            // Delete and re-create webhook topic with 1 partition instead of 6 (drift)
            try {
                adminClient.deleteTopics(List.of(webhookTopic)).all().get(5, TimeUnit.SECONDS);
                org.awaitility.Awaitility.await()
                        .atMost(10, TimeUnit.SECONDS)
                        .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(webhookTopic));
            } catch (Exception ignored) {}

            org.apache.kafka.clients.admin.NewTopic driftedTopic =
                    new org.apache.kafka.clients.admin.NewTopic(webhookTopic, 1, (short) 1)
                            .configs(Map.of("retention.ms", "259200000"));
            try {
                adminClient.createTopics(List.of(driftedTopic)).all().get(5, TimeUnit.SECONDS);

                // Await topic creation confirmation on broker
                org.awaitility.Awaitility.await()
                        .atMost(10, TimeUnit.SECONDS)
                        .pollInterval(200, TimeUnit.MILLISECONDS)
                        .until(() -> {
                            try {
                                var desc = adminClient.describeTopics(List.of(webhookTopic)).allTopicNames().get(2, TimeUnit.SECONDS).get(webhookTopic);
                                return desc != null && desc.partitions().size() == 1;
                            } catch (Exception e) {
                                return false;
                            }
                        });

                // Reconcile: must detect drift, record signal, and NOT mutate broker partition count
                topicReconciler.reconcileTopicConfigs(adminClient);

                assertThat(topicReconciler.hasPartitionDrift(webhookTopic)).isTrue();
                assertThat(topicReconciler.getDriftedTopics()).contains(webhookTopic);
                assertThat(topicReconciler.isTopicVerified(webhookTopic)).isFalse();

                // Verify broker partition count was NOT mutated
                TopicDescription desc = adminClient.describeTopics(List.of(webhookTopic))
                        .allTopicNames().get(5, TimeUnit.SECONDS).get(webhookTopic);
                assertThat(desc.partitions()).hasSize(1);
            } finally {
                // Restore contracted 6 partitions on webhook topic so broker remains clean
                try {
                    adminClient.deleteTopics(List.of(webhookTopic)).all().get(5, TimeUnit.SECONDS);
                    org.awaitility.Awaitility.await()
                            .atMost(10, TimeUnit.SECONDS)
                            .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(webhookTopic));
                    adminClient.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(webhookTopic, 6, (short) 1)
                            .configs(Map.of("retention.ms", "259200000")))).all().get(5, TimeUnit.SECONDS);
                    topicReconciler.reconcileTopicConfigs(adminClient);
                } catch (Exception ignored) {}
            }
        }
    }

    @Test
    @DisplayName("Topic deleted after initial reconciliation: reconciler detects deletion and recovers contracted partitions and retention")
    void topicDeletedAfterInitialReconciliation_reconcilerDetectsAndRecovers() throws Exception {
        try (AdminClient adminClient = createAdminClient()) {
            String emailTopic = KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL;

            // Step 1: Ensure initial reconciliation has provisioned emailTopic
            boolean initialReconciled = topicReconciler.reconcileNow();
            assertThat(initialReconciled).isTrue();
            assertThat(topicReconciler.isTopicVerified(emailTopic)).isTrue();

            TopicDescription initialDesc = adminClient.describeTopics(List.of(emailTopic))
                    .allTopicNames().get(5, TimeUnit.SECONDS).get(emailTopic);
            assertThat(initialDesc.partitions()).hasSize(6);

            // Step 2: Delete topic from the broker
            adminClient.deleteTopics(List.of(emailTopic)).all().get(5, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(() -> !adminClient.listTopics().names().get(5, TimeUnit.SECONDS).contains(emailTopic));

            // Step 3: Trigger reconciliation cycle
            boolean recovered = topicReconciler.reconcileNow();
            assertThat(recovered).isTrue();

            // Step 4: Verify the topic was detected as missing, re-created, and verified
            assertThat(topicReconciler.isTopicVerified(emailTopic)).isTrue();

            TopicDescription recoveredDesc = adminClient.describeTopics(List.of(emailTopic))
                    .allTopicNames().get(5, TimeUnit.SECONDS).get(emailTopic);
            assertThat(recoveredDesc.partitions())
                    .as("Recovered topic must have contracted 6 partitions, not 1")
                    .hasSize(6);

            ConfigResource emailResource = new ConfigResource(ConfigResource.Type.TOPIC, emailTopic);
            Config recoveredConfig = adminClient.describeConfigs(List.of(emailResource))
                    .all().get(5, TimeUnit.SECONDS).get(emailResource);
            assertThat(recoveredConfig.get("retention.ms").value())
                    .as("Recovered topic must have contracted 3-day retention.ms")
                    .isEqualTo("259200000");

            // Step 5: Verify publishing succeeds to recovered topic
            var future = dispatchProducer.send(
                    emailTopic,
                    "order-456",
                    "{\"msg\":\"payload after recovery\"}",
                    null,
                    tenantId
            );
            var sendResult = future.get(5, TimeUnit.SECONDS);
            assertThat(sendResult).isNotNull();
            assertThat(sendResult.getRecordMetadata().topic()).isEqualTo(emailTopic);
        }
    }

    @Test
    @DisplayName("F1: triggerReconciliation coalesces rapid triggers and bounds execution cycles")
    void triggerReconciliation_coalescesRapidTriggers_boundsExecutionCycles() throws Exception {
        long initialCycles = topicReconciler.getReconciliationCycleCount();

        // Fire 50 triggers across 5 concurrent threads in a tight burst
        int threadCount = 5;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 10; j++) {
                        topicReconciler.triggerReconciliation();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Wait a short moment for any coalesced run to complete
        Thread.sleep(1000);

        long cyclesAfterBurst = topicReconciler.getReconciliationCycleCount();
        long triggeredCycles = cyclesAfterBurst - initialCycles;

        log.info("Burst of 50 triggers resulted in {} reconciliation cycles", triggeredCycles);
        assertThat(triggeredCycles)
                .as("Coalesced triggers must not enqueue 50 separate cycles")
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("F2 & F3: Broker auto.create.topics.enable is detected as false and exposed via Reconciler and Actuator signals")
    void brokerAutoCreateAndDriftSignal_exposedViaReconcilerAndActuator() throws Exception {
        boolean reconciled = topicReconciler.reconcileNow();
        assertThat(reconciled).as("Explicit reconciliation cycle must succeed").isTrue();

        // F2: Verify auto.create.topics.enable was inspected and detected as false
        assertThat(topicReconciler.isBrokerAutoCreateEnabled()).isFalse();

        // F3: Verify InfoContributor
        var infoContributor = new com.notify.kafka.config.KafkaTopicInfoContributor(topicReconciler);
        org.springframework.boot.actuate.info.Info.Builder infoBuilder = new org.springframework.boot.actuate.info.Info.Builder();
        infoContributor.contribute(infoBuilder);
        var details = (Map<?, ?>) infoBuilder.build().getDetails().get("kafkaTopics");
        assertThat(details).isNotNull();
        assertThat(details.get("reconciled")).isEqualTo(true);
        assertThat(details.get("brokerAutoCreateEnabled")).isEqualTo(false);
        assertThat(details.get("hasPartitionDrift")).isEqualTo(false);
    }
}
