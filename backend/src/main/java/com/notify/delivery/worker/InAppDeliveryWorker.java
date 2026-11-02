package com.notify.delivery.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.config.ConditionalOnInAppWorker;
import com.notify.common.model.Channel;
import com.notify.delivery.model.PersistableOutcome;
import com.notify.delivery.service.ClaimDispositionClassifier;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.delivery.service.ReconciliationEvaluator;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.config.InAppWorkerKafkaConfig;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions.PoisonMessageException;
import com.notify.delivery.worker.model.DispatchInAppEvent;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.sse.stream.InAppFanoutPublisher;
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
 * The in-app delivery worker (/ ): one Kafka dispatch record in, one durable inbox row out.
 *
 * <p>It adds no orchestration of its own. Claim fencing, the single result transaction, reconciliation, post-commit
 * trigger repair and acknowledgment ownership all come from {@link AbstractDeliveryWorker}, and the tenant gate,
 * retry, exhaustion and deferral policy from {@code DeliveryOrchestrator} - the same instances the other channels
 * use. What is in-app specific is the persistence side effect inside the result transaction and the post-commit
 * fan-out hint.
 *
 * <p>{@code notifications.sender} stays null for IN_APP: the sender snapshot () is an email concept.
 */
@Component
@ConditionalOnInAppWorker
public class InAppDeliveryWorker extends AbstractDeliveryWorker {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long[] PERSIST_RETRY_DELAYS_MS = {50, 150, 300};

    private final InAppFanoutPublisher fanout;

    @Autowired
    public InAppDeliveryWorker(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryOrchestrator orchestrator,
            DeliveryResultService resultService,
            ClaimDispositionClassifier dispositionClassifier,
            ReconciliationEvaluator reconciliationEvaluator,
            DeliveryExceptionClassifier classifier,
            NotificationSchedulerService scheduler,
            @Qualifier("inappWorkerContainerController") WorkerContainerController controller,
            Clock clock,
            ObjectProvider<MeterRegistry> meters,
            InAppFanoutPublisher fanout
    ) {
        this(transitioner, notificationService, orchestrator, resultService, dispositionClassifier,
                reconciliationEvaluator, classifier, scheduler, controller, clock,
                meters.getIfAvailable(SimpleMeterRegistry::new), PERSIST_RETRY_DELAYS_MS, fanout);
    }

    /** For tests: explicit meter registry and persistence retry delays. */
    public InAppDeliveryWorker(
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
            long[] persistRetryDelaysMs,
            InAppFanoutPublisher fanout
    ) {
        super(transitioner, notificationService, orchestrator, resultService, dispositionClassifier,
                reconciliationEvaluator, classifier, scheduler, controller, clock, meters, persistRetryDelaysMs);
        this.fanout = fanout;
    }

    @KafkaListener(
            id = WorkerContainerController.INAPP_LISTENER_ID,
            topics = "${notify.worker.inapp.topic:notify.dispatch.inapp.v1}",
            groupId = "${notify.worker.inapp.group-id:notify-inapp-delivery-workers}",
            containerFactory = InAppWorkerKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "${notify.worker.inapp.auto-startup:true}"
    )
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        handleRecord(record, acknowledgment);
    }

    @Override
    protected String channelName() {
        return "inapp";
    }

    @Override
    protected DispatchInAppEvent parseEvent(byte[] payload) {
        return parse(payload);
    }

    @Override
    protected String defaultSender() {
        return null;
    }

    /**
     * Post-commit fan-out: publish a wake-up hint for this recipient (). The hint carries no ordering and no
     * payload authority, so a failure here loses nothing - the next drain reads the row from PostgreSQL. It runs only
     * for a committed or reconciled result, because that is when {@code postCommit} is called at all.
     */
    @Override
    protected void afterResultCommitted(ClaimSnapshot claim, PersistableOutcome outcome) {
        if (outcome instanceof PersistableOutcome.Delivered) {
            fanout.publishHint(claim.tenantId(), claim.recipient());
        }
    }

    /** Parses and validates the dispatch envelope; any violation is a poison message (and only here). */
    static DispatchInAppEvent parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new PoisonMessageException("Empty dispatch payload", null);
        }
        DispatchInAppEvent event;
        try {
            event = MAPPER.readValue(payload, DispatchInAppEvent.class);
        } catch (Exception e) {
            // Deliberately no cause: a Jackson message quotes a fragment of the payload, which must not reach a log.
            throw new PoisonMessageException("Unparseable dispatch payload (" + e.getClass().getSimpleName() + ")", null);
        }
        if (event == null) {
            throw new PoisonMessageException("Null dispatch payload", null);
        }
        if (event.schemaVersion() == null || event.schemaVersion() != 1) {
            throw new PoisonMessageException("Unsupported schemaVersion", null);
        }
        if (event.eventType() == null || !DispatchInAppEvent.EVENT_TYPES.contains(event.eventType())) {
            throw new PoisonMessageException("Unsupported eventType", null);
        }
        if (event.notificationId() == null || event.tenantId() == null) {
            throw new PoisonMessageException("Missing notificationId or tenantId", null);
        }
        if (!Channel.IN_APP.name().equals(event.channel())) {
            throw new PoisonMessageException("Not an IN_APP dispatch event", null);
        }
        if (event.generation() == null || event.generation() < 0) {
            throw new PoisonMessageException("Missing or negative generation", null);
        }
        return event;
    }
}