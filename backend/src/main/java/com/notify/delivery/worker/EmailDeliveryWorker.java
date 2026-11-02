package com.notify.delivery.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.config.ConditionalOnEmailWorker;
import com.notify.common.config.MailProperties;
import com.notify.common.model.Channel;
import com.notify.delivery.service.ClaimDispositionClassifier;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.DeliveryResultService;
import com.notify.delivery.service.ReconciliationEvaluator;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.config.EmailWorkerKafkaConfig;
import com.notify.delivery.worker.control.WorkerContainerController;
import com.notify.delivery.worker.exception.WorkerExceptions.PoisonMessageException;
import com.notify.delivery.worker.model.DispatchEmailEvent;
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
 * The email delivery worker: one Kafka dispatch record in, one durable result out (plan sections 3.2, 6 and 7).
 *
 * <p>Everything channel-independent — claim fencing, the result transaction, reconciliation, post-commit repair and
 * acknowledgment ownership — lives in {@link AbstractDeliveryWorker}, shared with the webhook worker since . This
 * class adds the listener binding, the EMAIL envelope validation, and the sender snapshot default ().
 */
@Component
@ConditionalOnEmailWorker
public class EmailDeliveryWorker extends AbstractDeliveryWorker {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long[] PERSIST_RETRY_DELAYS_MS = {50, 150, 300};

    private final MailProperties mailProperties;

    @Autowired
    public EmailDeliveryWorker(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryOrchestrator orchestrator,
            DeliveryResultService resultService,
            ClaimDispositionClassifier dispositionClassifier,
            ReconciliationEvaluator reconciliationEvaluator,
            DeliveryExceptionClassifier classifier,
            NotificationSchedulerService scheduler,
            @Qualifier("emailWorkerContainerController") WorkerContainerController controller,
            MailProperties mailProperties,
            Clock clock,
            ObjectProvider<MeterRegistry> meters
    ) {
        this(transitioner, notificationService, orchestrator, resultService, dispositionClassifier,
                reconciliationEvaluator, classifier, scheduler, controller, mailProperties, clock,
                meters.getIfAvailable(SimpleMeterRegistry::new), PERSIST_RETRY_DELAYS_MS);
    }

    /** For tests: explicit meter registry and persistence retry delays. */
    public EmailDeliveryWorker(
            NotificationTransitioner transitioner,
            NotificationService notificationService,
            DeliveryOrchestrator orchestrator,
            DeliveryResultService resultService,
            ClaimDispositionClassifier dispositionClassifier,
            ReconciliationEvaluator reconciliationEvaluator,
            DeliveryExceptionClassifier classifier,
            NotificationSchedulerService scheduler,
            WorkerContainerController controller,
            MailProperties mailProperties,
            Clock clock,
            MeterRegistry meters,
            long[] persistRetryDelaysMs
    ) {
        super(transitioner, notificationService, orchestrator, resultService, dispositionClassifier,
                reconciliationEvaluator, classifier, scheduler, controller, clock, meters, persistRetryDelaysMs);
        this.mailProperties = mailProperties;
    }

    @KafkaListener(
            id = WorkerContainerController.LISTENER_ID,
            topics = "${notify.worker.email.topic:notify.dispatch.email.v1}",
            groupId = "${notify.worker.email.group-id:notify-email-delivery-workers}",
            containerFactory = EmailWorkerKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "${notify.worker.email.auto-startup:true}"
    )
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        handleRecord(record, acknowledgment);
    }

    @Override
    protected String channelName() {
        return "email";
    }

    @Override
    protected DispatchEmailEvent parseEvent(byte[] payload) {
        return parse(payload);
    }

    /** a legacy EMAIL row with a null sender gets the configured sender written once, at its first claim. */
    @Override
    protected String defaultSender() {
        return mailProperties.from();
    }

    /** Parses and validates the dispatch envelope; any violation is a poison message (and only here). */
    static DispatchEmailEvent parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new PoisonMessageException("Empty dispatch payload", null);
        }
        DispatchEmailEvent event;
        try {
            event = MAPPER.readValue(payload, DispatchEmailEvent.class);
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
        if (event.eventType() == null || !DispatchEmailEvent.EVENT_TYPES.contains(event.eventType())) {
            throw new PoisonMessageException("Unsupported eventType", null);
        }
        if (event.notificationId() == null || event.tenantId() == null) {
            throw new PoisonMessageException("Missing notificationId or tenantId", null);
        }
        if (!Channel.EMAIL.name().equals(event.channel())) {
            throw new PoisonMessageException("Not an EMAIL dispatch event", null);
        }
        if (event.generation() == null || event.generation() < 0) {
            throw new PoisonMessageException("Missing or negative generation", null);
        }
        return event;
    }
}
