package com.notify.sse.stream;

import com.notify.sse.config.SseProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis hint listener (/ , ): it must carry no authority and must be impossible to misdirect.
 *
 * <p>Committed coverage went no further than the cap and the drain, so the listener's own parsing - the one place
 * where an attacker-influenced channel name or body reaches tenant routing - had no test at all (corrective
 * cycle, adjacent gap). Nothing here needs Redis: the listener is a plain object.
 */
class InAppFanoutHintTest {

    /** Records what the listener asked for instead of doing it. */
    private static final class RecordingRegistry extends SseStreamRegistry {
        private final List<String> hints = new ArrayList<>();

        private RecordingRegistry() {
            super(null, null, null, props(), new MonotonicTimeSource(), new SimpleMeterRegistry(),
                    Executors.newScheduledThreadPool(1), Runnable::run, Runnable::run);
        }

        @Override
        public void onHint(UUID tenantId, String recipientRef) {
            hints.add(tenantId + "/" + recipientRef);
        }
    }

    private static SseProperties props() {
        return new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 8);
    }

    private static byte[] body(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the listener is a MessageListener the container can call with no initialisation step")
    void theListenerNeedsNoInitialisation() {
        RecordingRegistry registry = new RecordingRegistry();
        UUID tenantId = UUID.randomUUID();
        org.springframework.data.redis.connection.MessageListener listener =
                new InAppFanoutSubscriber.HintListener(registry);
        String channel = InAppFanoutPublisher.CHANNEL_PREFIX + tenantId;

        // Exactly how RedisMessageListenerContainer delivers a pattern subscription's message. The previous
        // implementation wrapped this in a MessageListenerAdapter created with `new`, whose reflective invoker is
        // resolved in afterPropertiesSet() - never called - so every hint died with a NullPointerException and live
        // fan-out never worked. This call reproduces the delivery path directly.
        listener.onMessage(
                new org.springframework.data.redis.connection.DefaultMessage(
                        channel.getBytes(StandardCharsets.UTF_8), body("user-9")),
                (InAppFanoutPublisher.CHANNEL_PREFIX + "*").getBytes(StandardCharsets.UTF_8));

        assertThat(registry.hints).containsExactly(tenantId + "/user-9");
    }

    @Test
    @DisplayName("a well-formed hint routes to the tenant in the channel name and the recipient in the body")
    void aWellFormedHintRoutes() {
        RecordingRegistry registry = new RecordingRegistry();
        UUID tenantId = UUID.randomUUID();

        new InAppFanoutSubscriber.HintListener(registry)
                .handleMessage(body("user-7"), InAppFanoutPublisher.CHANNEL_PREFIX + tenantId);

        assertThat(registry.hints).containsExactly(tenantId + "/user-7");
    }

    @Test
    @DisplayName("a quoted body is unquoted, so a serializer change cannot silently stop waking the right stream")
    void aQuotedBodyIsUnquoted() {
        RecordingRegistry registry = new RecordingRegistry();
        UUID tenantId = UUID.randomUUID();

        new InAppFanoutSubscriber.HintListener(registry)
                .handleMessage(body("\"user-7\""), InAppFanoutPublisher.CHANNEL_PREFIX + tenantId);

        assertThat(registry.hints).containsExactly(tenantId + "/user-7");
    }

    @Test
    @DisplayName("an unparseable or foreign channel is ignored rather than guessed at")
    void badChannelsAreIgnored() {
        RecordingRegistry registry = new RecordingRegistry();
        InAppFanoutSubscriber.HintListener listener = new InAppFanoutSubscriber.HintListener(registry);

        listener.handleMessage(body("user-7"), null);
        listener.handleMessage(body("user-7"), "some:other:channel");
        listener.handleMessage(body("user-7"), InAppFanoutPublisher.CHANNEL_PREFIX + "not-a-uuid");
        listener.handleMessage(body("user-7"), InAppFanoutPublisher.CHANNEL_PREFIX);

        assertThat(registry.hints).isEmpty();
    }

    @Test
    @DisplayName("an empty body is a tenant-wide wake-up, never a recipient named the empty string")
    void anEmptyBodyWakesTheWholeTenant() {
        RecordingRegistry registry = new RecordingRegistry();
        UUID tenantId = UUID.randomUUID();

        new InAppFanoutSubscriber.HintListener(registry)
                .handleMessage(null, InAppFanoutPublisher.CHANNEL_PREFIX + tenantId);

        // onHint treats null and "" as "every recipient of this tenant"; the hint still carries no payload authority.
        assertThat(registry.hints).containsExactly(tenantId + "/");
    }
}
