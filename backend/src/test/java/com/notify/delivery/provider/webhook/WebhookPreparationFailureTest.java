package com.notify.delivery.provider.webhook;

import com.notify.common.model.Channel;
import com.notify.delivery.provider.ChannelPreparationException;
import com.notify.delivery.service.TransientDbRetry;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.security.DestinationResolver;
import com.notify.webhook.service.WebhookEndpointService;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The webhook half of the preparation boundary (/ database-recovery repair): what the adapter does when the
 * endpoint row cannot be read.
 *
 * <p>Two things are asserted that a result-shaped assertion could not catch: nothing is sent — neither a name
 * resolution nor an HTTP request happens — and the failure leaves the adapter as a
 * {@link ChannelPreparationException} rather than as a {@code DeliveryResult}, which is what keeps the orchestrator
 * from recording a provider attempt for a request that was never made.
 */
class WebhookPreparationFailureTest {

    private static final UUID TENANT = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID NOTIFICATION = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String URL = "https://receiver.example.com/hook";

    private static final ClaimSnapshot CLAIM = new ClaimSnapshot(NOTIFICATION, TENANT, 0, 1, 0, 1, 5, 0,
            Channel.WEBHOOK, URL, "Subject", "body", null, null, null,
            NOW.minusSeconds(60), NOW.minusSeconds(600));

    private WebhookEndpointService endpointService;
    private DestinationResolver destinationResolver;
    private WebhookSender sender;
    private WebhookChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        endpointService = mock(WebhookEndpointService.class);
        destinationResolver = mock(DestinationResolver.class);
        sender = mock(WebhookSender.class);
        WebhookProperties properties = new WebhookProperties(
                "", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2), 8192, 4096,
                "Notify-Webhook/1", List.of());
        adapter = new WebhookChannelAdapter(endpointService, destinationResolver, new WebhookPayloadFactory(),
                sender, new TransientDbRetry(new DeliveryExceptionClassifier(), new long[]{0, 0, 0}),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JDBCConnectionException connectionLost() {
        return new JDBCConnectionException("could not obtain connection",
                new SQLException("connection refused", "08006"));
    }

    @Test
    @DisplayName("the endpoint read failing leaves the adapter as a preparation failure, with nothing resolved and nothing sent")
    void preparationFailureIsTypedAndSendsNothing() {
        when(endpointService.resolveDestinationForDelivery(TENANT)).thenThrow(
                new ChannelPreparationException("Webhook endpoint lookup failed before any provider request",
                        connectionLost()));

        assertThatThrownBy(() -> adapter.send(CLAIM))
                .isInstanceOf(ChannelPreparationException.class)
                .hasRootCauseInstanceOf(SQLException.class);

        verify(destinationResolver, never()).resolve(anyString(), anyBoolean(), anyLong());
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("the short in-memory retry still absorbs a blip first, and gives up after its own bounded budget")
    void boundedRetryRunsBeforeTheFailureEscapes() {
        when(endpointService.resolveDestinationForDelivery(TENANT)).thenThrow(
                new ChannelPreparationException("lookup failed", connectionLost()));

        assertThatThrownBy(() -> adapter.send(CLAIM)).isInstanceOf(ChannelPreparationException.class);

        // TransientDbRetry is configured here with three delays, so four attempts in total; the classifier sees
        // CONNECTIVITY through the wrapper, which is why the retry engages at all.
        verify(endpointService, times(4)).resolveDestinationForDelivery(TENANT);
        assertThat(new DeliveryExceptionClassifier().classify(
                new ChannelPreparationException("lookup failed", connectionLost())))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("a non-retryable preparation cause escapes immediately, without consuming the in-memory retries")
    void nonRetryableCauseEscapesAtOnce() {
        when(endpointService.resolveDestinationForDelivery(TENANT)).thenThrow(
                new ChannelPreparationException("lookup failed",
                        new org.springframework.dao.DataIntegrityViolationException("relation does not exist")));

        assertThatThrownBy(() -> adapter.send(CLAIM)).isInstanceOf(ChannelPreparationException.class);

        verify(endpointService, times(1)).resolveDestinationForDelivery(TENANT);
        verify(sender, never()).send(any(), any());
    }
}
