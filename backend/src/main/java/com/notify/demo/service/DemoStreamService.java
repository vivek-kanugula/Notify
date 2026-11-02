package com.notify.demo.service;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.demo.config.DemoProperties;
import com.notify.demo.dto.DemoInboxItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages public SSE subscriptions and event broadcasting for the demo sandbox inbox (/ ).
 *
 * <p>Enforces projection rules: only opted-in demo tenants are broadcast, recipients are
 * masked, subjects sanitized, and bodies/metadata strictly excluded.
 */
@Service
public class DemoStreamService {

    private static final Logger log = LoggerFactory.getLogger(DemoStreamService.class);
    private static final long STREAM_TIMEOUT_MS = 180_000L; // 3 minutes

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final DemoProperties demoProperties;
    private final Clock clock;
    private final AtomicInteger pulseCounter = new AtomicInteger(0);

    public DemoStreamService(DemoProperties demoProperties, Clock clock) {
        this.demoProperties = demoProperties;
        this.clock = clock;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));

        try {
            // Initial keep-alive comment
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    public int activeSubscriberCount() {
        return emitters.size();
    }

    /**
     * Broadcasts an compliant demo inbox item to all active public SSE subscribers.
     */
    public void broadcast(DemoInboxItemResponse item) {
        if (emitters.isEmpty() || item == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(item, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Periodic 15s heartbeat to prevent proxy timeout and prune stale client connections.
     */
    @Scheduled(fixedDelay = 15000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Lightweight simulation pulse for the public demo inbox.
     * When visitors are connected and demo mode is active, emits realistic synthetic updates.
     */
    @Scheduled(fixedDelay = 10000)
    public void simulationPulse() {
        if (emitters.isEmpty() || !demoProperties.inboxEnabled()) {
            return;
        }

        int count = pulseCounter.incrementAndGet();
        List<String> slugs = demoProperties.tenantSlugs();
        String slug = slugs.get(count % slugs.size());
        String name = "acme".equalsIgnoreCase(slug) ? "Acme Corp" : "Globex";

        Channel[] channels = {Channel.EMAIL, Channel.WEBHOOK, Channel.IN_APP};
        Channel channel = channels[count % channels.length];

        NotificationStatus[] statuses = {
                NotificationStatus.DELIVERED,
                NotificationStatus.DELIVERED,
                NotificationStatus.DELIVERED,
                NotificationStatus.PROCESSING,
                NotificationStatus.RETRYING
        };
        NotificationStatus status = statuses[count % statuses.length];

        String recipient = channel == Channel.WEBHOOK ? "https://example.com/webhook" : "user" + (count % 20) + "@example.com";
        String subject = switch (channel) {
            case EMAIL -> "Order Confirmation #" + (1000 + count);
            case WEBHOOK -> "Payment Succeeded · Invoice #" + (5000 + count);
            case IN_APP -> "New Security Alert · Session from 192.0.2." + (count % 250);
        };

        DemoInboxItemResponse syntheticItem = new DemoInboxItemResponse(
                UUID.randomUUID(),
                slug,
                name,
                channel,
                status,
                DemoInboxService.maskRecipient(recipient),
                DemoInboxService.sanitizeSubject(subject),
                Instant.now(clock)
        );

        broadcast(syntheticItem);
    }
}
