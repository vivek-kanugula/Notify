package com.notify.kafka.config;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reconciles Kafka broker-side topic configuration per CONTRACTS.md:
 * - 3 days (259,200,000 ms) retention for dispatch topics and status events
 * - 14 days (1,209,600,000 ms) retention for DLT topics and DLQ
 * - Contracted partition counts: 6 partitions for email and webhook, 3 partitions for in-app, DLT, DLQ, and status events
 *
 * Operational lifecycle & resiliency:
 * - Non-blocking startup: executes on a managed background daemon thread (phase Integer.MAX_VALUE - 100,
 *   thread 'kafka-topic-reconciler') so application startup and readiness are never delayed by broker latency or downtime.
 * - Periodic & triggerable background reconciliation: runs every 5 seconds and can be triggered asynchronously upon missing topic errors.
 * - Broker-level auto-create enforcement: Broker-side auto-topic-creation MUST be disabled (KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false")
 *   to authoritatively prevent wrong-partition auto-created topics upon deletion or cold startup.
 * - In-memory verification cache: maintains verifiedTopics populated during background reconciliation cycles;
 *   invalidated upon UnknownTopicOrPartitionException failures without blocking the outbox relay.
 * - Partition drift detection: inspects broker partition counts and logs actionable WARN alerts without mutating existing broker partitions.
 * - Bounded timeouts: AdminClient operations use short, bounded timeouts (3s).
 * - Clean shutdown: stops background worker with bounded 1-second timeout.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${spring.kafka.bootstrap-servers:}'.trim().isEmpty()")
public class KafkaTopicConfigReconciler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicConfigReconciler.class);

    public record TopicSpec(String name, int partitions, long retentionMs) {}

    public static final long RETENTION_3_DAYS_MS = 3L * 24 * 60 * 60 * 1000;
    public static final long RETENTION_14_DAYS_MS = 14L * 24 * 60 * 60 * 1000;

    public static final List<TopicSpec> EXPECTED_TOPICS = List.of(
            new TopicSpec(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL, 6, RETENTION_3_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK, 6, RETENTION_3_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_DISPATCH_INAPP, 3, RETENTION_3_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL_DLT, 3, RETENTION_14_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK_DLT, 3, RETENTION_14_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_DISPATCH_INAPP_DLT, 3, RETENTION_14_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_DLQ, 3, RETENTION_14_DAYS_MS),
            new TopicSpec(KafkaTopicsConfig.TOPIC_STATUS_EVENTS, 3, RETENTION_3_DAYS_MS)
    );

    private final KafkaAdmin kafkaAdmin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconciled = new AtomicBoolean(false);
    private final AtomicBoolean triggerPending = new AtomicBoolean(false);
    private final AtomicBoolean brokerAutoCreateEnabled = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong reconciliationCycleCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final Set<String> verifiedTopics = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> driftedTopics = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    private AdminClient adminClient;

    public KafkaTopicConfigReconciler(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    private synchronized AdminClient getOrCreateAdminClient() {
        if (this.adminClient == null) {
            Map<String, Object> props = kafkaAdmin.getConfigurationProperties();
            this.adminClient = AdminClient.create(props);
        }
        return this.adminClient;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kafka-topic-reconciler");
                t.setDaemon(true);
                return t;
            });
            // Schedule immediate background reconciliation and recurring retry/drift check every 5s
            executor.scheduleWithFixedDelay(this::attemptReconciliation, 0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (executor != null) {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (this) {
                if (this.adminClient != null) {
                    try {
                        this.adminClient.close(java.time.Duration.ofSeconds(1));
                    } catch (Exception ignored) {
                    }
                    this.adminClient = null;
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public boolean isReconciled() {
        return reconciled.get();
    }

    public boolean isBrokerAutoCreateEnabled() {
        return brokerAutoCreateEnabled.get();
    }

    public long getReconciliationCycleCount() {
        return reconciliationCycleCount.get();
    }

    /**
     * Checks if the topic is verified based on the in-memory cache populated by background reconciliation cycles.
     * Fast non-blocking O(1) check; does not perform synchronous broker network I/O.
     */
    public boolean isTopicVerified(String topic) {
        return verifiedTopics.contains(topic);
    }

    public void invalidateTopic(String topic) {
        verifiedTopics.remove(topic);
    }

    public Set<String> getDriftedTopics() {
        return java.util.Collections.unmodifiableSet(driftedTopics);
    }

    public boolean hasPartitionDrift(String topic) {
        return driftedTopics.contains(topic);
    }

    /**
     * Triggers asynchronous topic reconciliation in the background executor.
     * Coalesces concurrent or back-to-back triggers: at most one triggered task is queued
     * or executing at any time. Never blocks the caller.
     */
    public void triggerReconciliation() {
        if (running.get() && executor != null && !executor.isShutdown()) {
            if (triggerPending.compareAndSet(false, true)) {
                executor.submit(() -> {
                    try {
                        attemptReconciliation();
                    } finally {
                        triggerPending.set(false);
                    }
                });
            }
        }
    }

    public synchronized boolean ensureTopicProvisioned(String topic) {
        if (verifiedTopics.contains(topic)) {
            return true;
        }
        return reconcileNow();
    }

    private void attemptReconciliation() {
        if (!running.get()) {
            return;
        }
        try {
            boolean success = reconcileNow();
            if (success) {
                log.debug("Kafka topic configuration periodic reconciliation cycle succeeded.");
            }
        } catch (Exception e) {
            log.warn("Kafka topic config reconciliation attempt failed (broker may be offline): {}. Retrying in background...", e.getMessage());
        }
    }

    /**
     * Executes topic reconciliation against KafkaAdmin.
     * Returns true if successful, false if broker is unreachable or reconciliation fails.
     */
    public synchronized boolean reconcileNow() {
        try {
            AdminClient client = getOrCreateAdminClient();
            reconcileTopicConfigs(client);
            reconciled.set(true);
            reconciliationCycleCount.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.warn("Reconciliation execution encountered error: {}", e.getMessage());
            reconciliationCycleCount.incrementAndGet();
            if (this.adminClient != null) {
                try {
                    this.adminClient.close(java.time.Duration.ofSeconds(1));
                } catch (Exception ignored) {
                }
                this.adminClient = null;
            }
            return false;
        }
    }

    public static TopicSpec findSpec(String topicName) {
        for (TopicSpec spec : EXPECTED_TOPICS) {
            if (spec.name().equals(topicName)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * Inspects broker-side topic configuration:
     * 0. Inspects broker-level auto.create.topics.enable config (warning only; no broker mutation).
     * 1. Detects and reports partition drift (warning only; no broker mutation per CONTRACTS.md).
     * 2. Creates missing topics.
     * 3. Alters retention.ms on existing topics if mismatched.
     */
    public void reconcileTopicConfigs(AdminClient adminClient) throws Exception {
        // 0. Inspect broker-level auto.create.topics.enable config
        try {
            Collection<org.apache.kafka.common.Node> nodes = adminClient.describeCluster().nodes().get(3, TimeUnit.SECONDS);
            if (!nodes.isEmpty()) {
                org.apache.kafka.common.Node node = nodes.iterator().next();
                ConfigResource brokerResource = new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(node.id()));
                Map<ConfigResource, Config> brokerConfigs = adminClient.describeConfigs(List.of(brokerResource)).all().get(3, TimeUnit.SECONDS);
                Config bConfig = brokerConfigs.get(brokerResource);
                if (bConfig != null) {
                    ConfigEntry autoCreateEntry = bConfig.get("auto.create.topics.enable");
                    if (autoCreateEntry != null) {
                        boolean autoCreate = "true".equalsIgnoreCase(autoCreateEntry.value());
                        boolean previous = brokerAutoCreateEnabled.getAndSet(autoCreate);
                        if (autoCreate && !previous) {
                            log.warn("KAFKA CONFIGURATION WARNING: Broker node {} has auto.create.topics.enable=true. Deletion races can cause auto-creation of topics with default 1 partition. Set KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false' in broker environment and recreate container.", node.id());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect broker auto.create.topics.enable config: {}", e.getMessage());
        }

        Set<String> existingTopics = adminClient.listTopics().names().get(3, TimeUnit.SECONDS);

        List<NewTopic> topicsToCreate = new ArrayList<>();
        Map<ConfigResource, Collection<AlterConfigOp>> configsToAlter = new HashMap<>();

        List<String> existingExpectedTopics = new ArrayList<>();
        for (TopicSpec spec : EXPECTED_TOPICS) {
            if (!existingTopics.contains(spec.name())) {
                NewTopic newTopic = new NewTopic(spec.name(), spec.partitions(), (short) 1)
                        .configs(Map.of("retention.ms", String.valueOf(spec.retentionMs())));
                topicsToCreate.add(newTopic);
            } else {
                existingExpectedTopics.add(spec.name());
            }
        }

        // 1. Detect partition drift on existing topics (report only; do not auto-mutate)
        if (!existingExpectedTopics.isEmpty()) {
            try {
                Map<String, TopicDescription> descriptions = adminClient.describeTopics(existingExpectedTopics).allTopicNames().get(3, TimeUnit.SECONDS);
                for (TopicSpec spec : EXPECTED_TOPICS) {
                    TopicDescription desc = descriptions.get(spec.name());
                    if (desc != null) {
                        int brokerPartitions = desc.partitions().size();
                        if (brokerPartitions != spec.partitions()) {
                            boolean newlyDrifted = driftedTopics.add(spec.name());
                            if (newlyDrifted) {
                                log.warn("PARTITION DRIFT DETECTED for topic '{}': expected {} partitions per CONTRACTS.md, but broker has {} partitions. Manual partition migration required; broker partition count will not be mutated automatically.",
                                        spec.name(), spec.partitions(), brokerPartitions);
                            }
                        } else {
                            boolean wasDrifted = driftedTopics.remove(spec.name());
                            if (wasDrifted) {
                                log.info("PARTITION DRIFT RESOLVED for topic '{}': broker partition count now matches contracted {} partitions.",
                                        spec.name(), spec.partitions());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not inspect topic descriptions for partition drift check: {}", e.getMessage());
            }
        }

        // 2. Create missing topics
        if (!topicsToCreate.isEmpty()) {
            try {
                adminClient.createTopics(topicsToCreate).all().get(3, TimeUnit.SECONDS);
                log.info("Created missing Kafka topics: {}", topicsToCreate.stream().map(NewTopic::name).toList());
            } catch (Exception e) {
                if (e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
                    log.debug("Topic already exists, continuing: {}", e.getMessage());
                } else {
                    throw e;
                }
            }
        }

        // 3. Check and reconcile retention.ms configs of existing topics
        List<ConfigResource> resources = EXPECTED_TOPICS.stream()
                .map(s -> new ConfigResource(ConfigResource.Type.TOPIC, s.name()))
                .toList();

        Map<ConfigResource, Config> currentConfigs = adminClient.describeConfigs(resources).all().get(3, TimeUnit.SECONDS);

        for (TopicSpec spec : EXPECTED_TOPICS) {
            ConfigResource res = new ConfigResource(ConfigResource.Type.TOPIC, spec.name());
            Config config = currentConfigs.get(res);
            if (config != null) {
                ConfigEntry retentionEntry = config.get("retention.ms");
                String expected = String.valueOf(spec.retentionMs());
                if (retentionEntry == null || !expected.equals(retentionEntry.value())) {
                    AlterConfigOp op = new AlterConfigOp(
                            new ConfigEntry("retention.ms", expected),
                            AlterConfigOp.OpType.SET
                    );
                    configsToAlter.put(res, List.of(op));
                }
            }
        }

        if (!configsToAlter.isEmpty()) {
            adminClient.incrementalAlterConfigs(configsToAlter).all().get(3, TimeUnit.SECONDS);
            log.info("Updated retention.ms on existing Kafka topics: {}",
                    configsToAlter.keySet().stream().map(ConfigResource::name).toList());
        }

        // Update verified topics set based on broker inspection
        Set<String> currentBrokerTopics = adminClient.listTopics().names().get(3, TimeUnit.SECONDS);
        for (TopicSpec spec : EXPECTED_TOPICS) {
            if (currentBrokerTopics.contains(spec.name()) && !driftedTopics.contains(spec.name())) {
                verifiedTopics.add(spec.name());
            } else {
                verifiedTopics.remove(spec.name());
            }
        }
    }
}
