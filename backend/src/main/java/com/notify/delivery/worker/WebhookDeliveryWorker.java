package com.notify.delivery.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.config.ConditionalOnWebhookWorker;
import com.notify.common.model.Channel;
import com.notify.delivery.service.ClaimDispositionClassifier;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.delivery.service.ReconciliationEvaluator;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.config.WebhookWorkerKafkaConfig;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions.PoisonMessageException;
import com.notify.delivery.worker.model.DispatchWebhookEvent;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationSchedulerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * The webhook delivery worker (/ ): one Kafka dispatch record in, one durable result out.
 *
 * <p>It adds no orchestration of its own. Claim fencing, the single result transaction, reconciliation, post-commit
 * trigger repair and acknowledgment ownership all come from {@link AbstractDeliveryWorker}, and the tenant gate,
 * retry, exhaustion and deferral policy from {@code DeliveryOrchestrator} — the same instances the email worker uses.
 * What is webhook-specific lives behind the channel adapter: destination resolution, signing and the HTTP call.
 *
 * <p>{@code notifications.sender} stays null for WEBHOOK: the sender snapshot () is an email concept.
 */
@Component
@ConditionalOnWebhookWorker
public class WebhookDeliveryWorker extends AbstractDeliveryWorker {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long[] PERSIST_RETRY_DELAYS_MS = {50, 150, 300};

    @Autowired
    public WebhookDeliveryWorker(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryOrchestrator orchestrator,
            DeliveryResultService resultService,
            ClaimDispositionClassifier dispositionClassifier,
            ReconciliationEvaluator reconciliationEvaluator,
            DeliveryExceptionClassifier classifier,
            NotificationSchedulerService scheduler,
            @Qualifier("webhookWorkerContainerController") WorkerContainerController controller,
            Clock clock,
            ObjectProvider<MeterRegistry> meters
    ) {
        this(transitioner, notificationService, orchestrator, resultService, dispositionClassifier,
                reconciliationEvaluator, classifier, scheduler, controller, clock,
                meters.getIfAvailable(SimpleMeterRegistry::new), PERSIST_RETRY_DELAYS_MS);
    }

    /** For tests: explicit meter registry and persistence retry delays. */
    public WebhookDeliveryWorker(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryOrchestrator orchestrator,
            DeliveryResultService resultService,
            ClaimDispositionClassifier dispositionClassifier,
            ReconciliationEvaluator reconciliationEvaluator,
            DeliveryExceptionClassifier classifier,
            NotificationSchedulerService scheduler,
            WorkerContainerController controller,
            Clock clock,
            MeterRegistry meters,
            long[] persistRetryDelaysMs
    ) {
        super(transitioner, notificationService, orchestrator, resultService, dispositionClassifier,
                reconciliationEvaluator, classifier, scheduler, controller, clock, meters, persistRetryDelaysMs);
    }

    @KafkaListener(
            id = WorkerContainerController.WEBHOOK_LISTENER_ID,
            topics = "${notify.worker.webhook.topic:notify.dispatch.webhook.v1}",
            groupId = "${notify.worker.webhook.group-id:notify-webhook-delivery-workers}",
            containerFactory = WebhookWorkerKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "${notify.worker.webhook.auto-startup:true}"
    )
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        handleRecord(record, acknowledgment);
    }

    @Override
    protected String channelName() {
        return "webhook";
    }

    @Override
    protected DispatchWebhookEvent parseEvent(byte[] payload) {
        return parse(payload);
    }

    @Override
    protected String defaultSender() {
        return null;
    }

    /** Parses and validates the dispatch envelope; any violation is a poison message (and only here). */
    static DispatchWebhookEvent parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new PoisonMessageException("Empty dispatch payload", null);
        }
        DispatchWebhookEvent event;
        try {
            event = MAPPER.readValue(payload, DispatchWebhookEvent.class);
        } catch (Exception e) {
            // Deliberately no cause: a Jackson message quotes a fragment of the payload, which must not reach any log.
            throw new PoisonMessageException("Unparseable dispatch payload (" + e.getClass().getSimpleName() + ")", null);
        }
        if (event == null) {
            throw new PoisonMessageException("Null dispatch payload", null);
        }
        if (event.schemaVersion() == null || event.schemaVersion() != 1) {
            throw new PoisonMessageException("Unsupported schemaVersion", null);
        }
        if (event.eventType() == null || !DispatchWebhookEvent.EVENT_TYPES.contains(event.eventType())) {
            throw new PoisonMessageException("Unsupported eventType", null);
        }
        if (event.notificationId() == null || event.tenantId() == null) {
            throw new PoisonMessageException("Missing notificationId or tenantId", null);
        }
        if (!Channel.WEBHOOK.name().equals(event.channel())) {
            throw new PoisonMessageException("Not a WEBHOOK dispatch event", null);
        }
        if (event.generation() == null || event.generation() < 0) {
            throw new PoisonMessageException("Missing or negative generation", null);
        }
        return event;
    }
}
