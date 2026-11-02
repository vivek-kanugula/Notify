package com.notify.dlq.consumer;

import com.notify.dlq.config.DlqConsumerProperties;
import org.apache.kafka.clients.admin.Admin;
import com.notify.kafka.config.KafkaTopicsConfig;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Decides whether a dead-letter listener may start (/ , , requirements 1 and 4).
 *
 * <p>Both listeners are created with {@code autoStartup=false}, so **nothing consumes until this gate says so**.
 * On {@code ApplicationReadyEvent} it does exactly one of three things per enabled consumer:
 *
 * <ul>
 *   <li><b>no input configured</b> - start the listener. A partition with no committed position then fails its
 *       assignment under {@code auto.offset.reset=none}, and the error handler stops the listener with actionable
 *       evidence. Ordinary missing-position behaviour is stop-with-evidence, not a silent seek;</li>
 *   <li><b>input configured and applied successfully</b> - start the listener;</li>
 *   <li><b>input configured and refused</b> - <b>leave the listener stopped</b> and log the refusal with the group,
 *       the file and the reason. An initialization failure never degrades into consuming from an unknown
 *       position.</li>
 * </ul>
 *
 * <p>It creates its own short-lived {@link Admin} client and closes it: no admin client lives beyond the gate, and
 * nothing here touches a topic that is not named in the input.
 */
public class DlqConsumerStartupGate {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumerStartupGate.class);

    private final DlqConsumerProperties props;
    private final String bootstrapServers;
    private final String environment;
    private final ObjectProvider<DlqConsumerContainerController> controllers;

    private final Map<String, String> refusals = new LinkedHashMap<>();
    private final List<String> applied = new ArrayList<>();

    public DlqConsumerStartupGate(
            DlqConsumerProperties props,
            String bootstrapServers,
            String environment,
            ObjectProvider<DlqConsumerContainerController> controllers
    ) {
        this.props = props;
        this.bootstrapServers = bootstrapServers;
        this.environment = environment;
        this.controllers = controllers;
    }

    /** Refusals by listener id, for the tests and for an operator reading the startup log. */
    public Map<String, String> refusals() {
        return Map.copyOf(refusals);
    }

    public List<String> applied() {
        return List.copyOf(applied);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openOrRefuse() {
        if (!props.anyConsumerEnabled()) {
            return;
        }
        if (!props.offsetInitializationConfigured()) {
            log.info("No offset initialization input configured: dead-letter listeners start and resume committed "
                    + "offsets only; a missing position stops the listener with actionable evidence");
            controllers.forEach(controller -> controller.start("no initialization input configured"));
            return;
        }

        Path file = Path.of(props.offsetInitFile());
        if (!Files.isReadable(file)) {
            refuseAll("offset initialization input " + file + " is not readable");
            return;
        }

        try (Admin admin = Admin.create(adminProperties())) {
            DlqOffsetInitializer initializer = new DlqOffsetInitializer(admin, environment);
            OffsetInitializationInput input = initializer.read(file);
            controllers.forEach(controller -> initializeFor(initializer, input, controller));
        } catch (RuntimeException e) {
            refuseAll("offset initialization failed: " + e.getMessage());
        }
    }

    private void initializeFor(
            DlqOffsetInitializer initializer, OffsetInitializationInput input,
            DlqConsumerContainerController controller) {
        String groupId = groupIdFor(controller.listenerId());
        if (groupId == null || !groupId.equals(input.groupId())) {
            // The input names one group; a listener it does not name is left stopped rather than started blind.
            String reason = "offset initialization input names group " + input.groupId()
                    + ", which is not the group of listener " + controller.listenerId();
            refusals.put(controller.listenerId(), reason);
            log.error("Dead-letter listener {} stays stopped: {}", controller.listenerId(), reason);
            return;
        }
        try {
            DlqOffsetInitializer.Result result = initializer.apply(input, groupId, topicsFor(controller.listenerId()));
            applied.add(controller.listenerId() + ": committed " + result.committed()
                    + ", skipped " + result.skippedExisting());
            result.notes().forEach(note -> log.info("Offset initialization [{}] {}", groupId, note));
            controller.start("offset initialization completed");
        } catch (DlqOffsetInitializer.InitializationRefusedException e) {
            refusals.put(controller.listenerId(), e.getMessage());
            log.error("Dead-letter listener {} stays stopped: {}", controller.listenerId(), e.getMessage());
        }
    }

    private String groupIdFor(String listenerId) {
        if (DlqEventConsumer.LISTENER_ID.equals(listenerId)) {
            return props.consumer().groupId();
        }
        if (DltRecordConsumer.LISTENER_ID.equals(listenerId)) {
            return props.dltConsumer().groupId();
        }
        return null;
    }


    /**
     * The topics the given listener actually subscribes to, taken from the same configuration the listener uses.
     *
     * <p>This is what restricts an initialization input to intended topics, and it is deliberately derived rather
     * than listed: there is no second operator-maintained allowlist that could drift from the subscription.
     */
    private Set<String> topicsFor(String listenerId) {
        if (DlqEventConsumer.LISTENER_ID.equals(listenerId)) {
            return Set.of(props.consumer().topic());
        }
        if (DltRecordConsumer.LISTENER_ID.equals(listenerId)) {
            return Set.of(
                    KafkaTopicsConfig.TOPIC_DISPATCH_EMAIL_DLT,
                    KafkaTopicsConfig.TOPIC_DISPATCH_WEBHOOK_DLT,
                    KafkaTopicsConfig.TOPIC_DISPATCH_INAPP_DLT);
        }
        return Set.of();
    }
    private void refuseAll(String reason) {
        controllers.forEach(controller -> {
            refusals.put(controller.listenerId(), reason);
            log.error("Dead-letter listener {} stays stopped: {}", controller.listenerId(), reason);
        });
    }

    private Properties adminProperties() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "notify-dlq-offset-initializer");
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        return properties;
    }
}
