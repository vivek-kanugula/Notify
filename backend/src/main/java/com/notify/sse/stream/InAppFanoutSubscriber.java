package com.notify.sse.stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Listens for in-app wake-up hints on {@code sse:inapp:*} and wakes the matching streams (/ , ).
 *
 * <p>The listener is intentionally dumb: it parses the tenant from the channel name and treats the body as a
 * recipient hint, then asks the registry to drain. It never emits anything itself, because a hint has no payload
 * authority.
 *
 * <p><b>Redis must never be able to break startup.</b> The container is registered with auto-start off and started
 * afterwards by {@link FanoutSubscriberStarter}, which retries quietly. A listener that failed the application
 * context when Redis was unreachable would contradict the whole design: the in-app channel keeps working from
 * PostgreSQL at heartbeat latency without Redis, and ingress must not care at all.
 *
 * <p>Once started, Spring Data Redis reconnects on its own. {@link FanoutSubscriberStarter} drains every local
 * stream after <b>its</b> successful start attempt, so a late-arriving Redis does not cost a heartbeat. Spring's own
 * internal re-subscribe after a dropped connection does <em>not</em> trigger an extra drain - there is no callback
 * for it - so in that case the recovery latency is one heartbeat (25 s by default). Stated rather than implied: no
 * message is lost either way, because the drain reads PostgreSQL and the cursor only advances across rows it sent.
 */
@Configuration
public class InAppFanoutSubscriber {

    private static final Logger log = LoggerFactory.getLogger(InAppFanoutSubscriber.class);

    @Bean
    public RedisMessageListenerContainer inappFanoutListenerContainer(
            RedisConnectionFactory connectionFactory, SseStreamRegistry registry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(Duration.ofSeconds(5).toMillis());
        container.setAutoStartup(false);
        // A direct MessageListener, NOT a MessageListenerAdapter (corrective cycle). The adapter resolves its
        // target method reflectively in afterPropertiesSet(), which Spring never calls on an object created with
        // `new` - so every hint failed with "Cannot invoke MethodInvoker.getMethodName() because this.invoker is
        // null" and live fan-out never worked at all. Nothing noticed, because the drain is also driven by the
        // heartbeat: streams stayed correct and merely caught up up to 25 s late. This form has no init step to
        // forget.
        container.addMessageListener(
                new HintListener(registry), new PatternTopic(InAppFanoutPublisher.CHANNEL_PREFIX + "*"));
        return container;
    }

    /** Starts the subscriber after the application is ready, retrying until Redis answers. */
    @Component
    public static class FanoutSubscriberStarter {

        private final RedisMessageListenerContainer container;
        private final SseStreamRegistry registry;
        private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notify-sse-subscriber");
            thread.setDaemon(true);
            return thread;
        });

        public FanoutSubscriberStarter(RedisMessageListenerContainer container, SseStreamRegistry registry) {
            this.container = container;
            this.registry = registry;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            attempt();
        }

        private void attempt() {
            try {
                if (!container.isRunning()) {
                    container.start();
                }
                // A (re)subscribe may have missed hints; recover immediately instead of waiting for a heartbeat.
                registry.drainAll();
            } catch (RuntimeException e) {
                log.warn("In-app fan-out subscriber could not start; streams stay correct from PostgreSQL and this "
                        + "will be retried ({})", e.getClass().getName());
                retries.schedule(this::attempt, 15, TimeUnit.SECONDS);
            }
        }

        @PreDestroy
        public void stop() {
            retries.shutdownNow();
            try {
                container.stop();
            } catch (RuntimeException e) {
                log.debug("Subscriber stop threw ({})", e.getClass().getSimpleName());
            }
        }
    }

    /** The listener itself: no reflection, no initialisation step, nothing to wire incorrectly. */
    public static final class HintListener implements MessageListener {

        private final SseStreamRegistry registry;

        public HintListener(SseStreamRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            handleMessage(message.getBody(), new String(message.getChannel(), StandardCharsets.UTF_8));
        }

        /** Separated from {@link #onMessage} so the parsing rules are testable without a Redis {@code Message}. */
        public void handleMessage(byte[] body, String channel) {
            if (channel == null || !channel.startsWith(InAppFanoutPublisher.CHANNEL_PREFIX)) {
                return;
            }
            String tenantPart = channel.substring(InAppFanoutPublisher.CHANNEL_PREFIX.length());
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantPart);
            } catch (IllegalArgumentException e) {
                log.debug("Ignoring a hint on an unparseable channel");
                return;
            }
            String recipient = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            registry.onHint(tenantId, unquote(recipient));
        }

        /** StringRedisTemplate publishes a bare string; a JSON serializer would add quotes. Tolerate both. */
        private static String unquote(String value) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }
}
