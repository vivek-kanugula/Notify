package com.notify.delivery.provider.webhook;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.delivery.service.TransientDbRetry;
import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.security.DestinationRejectedException;
import com.notify.webhook.security.DestinationRejectedException.Reason;
import com.notify.webhook.security.DestinationResolver;
import com.notify.webhook.security.DestinationResolver.PinnedDestination;
import com.notify.webhook.security.WebhookUrlPolicy.ValidatedUrl;
import com.notify.webhook.service.WebhookEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the adapter classifies what happens before the request goes out (F1, F8).
 *
 * <p>Two distinctions are the whole point here. A <em>name resolution</em> failure is remote infrastructure that
 * heals, so it must consume the ordinary retry budget () instead of failing the notification on attempt one. A
 * refusal decided before any provider contact must be attributed to {@code notify-system}, not to the provider that
 * was never called (/ CONTRACTS).
 */
class WebhookChannelAdapterTest {

    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID NOTIFICATION = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final String URL = "https://receiver.example.com/hook";

    private WebhookEndpointService endpointService;
    private DestinationResolver destinationResolver;
    private WebhookSender sender;
    private WebhookChannelAdapter adapter;

    private static final ClaimSnapshot CLAIM = new ClaimSnapshot(NOTIFICATION, TENANT, 0, 1, 0, 1, 5, 0,
            Channel.WEBHOOK, URL, "Subject", "body", null, null, null,
            NOW.minusSeconds(60), NOW.minusSeconds(600));

    @BeforeEach
    void setUp() {
        endpointService = mock(WebhookEndpointService.class);
        destinationResolver = mock(DestinationResolver.class);
        sender = mock(WebhookSender.class);
        WebhookProperties properties = new WebhookProperties(
                "", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2), 8192, 4096, "Notify-Webhook/1", List.of());
        adapter = new WebhookChannelAdapter(endpointService, destinationResolver, new WebhookPayloadFactory(),
                sender, new TransientDbRetry(new DeliveryExceptionClassifier(), new long[]{0, 0}),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void endpointConfigured(boolean enabled) {
        // The delivery path uses the preparation-seam method (/ database-recovery repair); the request-driven
        // resolveDestination is unchanged and still covered by WebhookEndpointServiceTest and WebhookPingServiceTest.
        when(endpointService.resolveDestinationForDelivery(TENANT)).thenReturn(Optional.of(new WebhookDestination(
                UUID.randomUUID(), TENANT, URL, false, enabled, "whsec_" + "A".repeat(43))));
    }

    @Test
    @DisplayName("F1 an unresolvable host is RETRYABLE WEBHOOK_UNREACHABLE, so the attempt budget is consumed normally")
    void dnsFailureIsRetryable() {
        endpointConfigured(true);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenThrow(new DestinationRejectedException(Reason.DNS_FAILURE,
                        "host receiver.example.com could not be resolved"));

        DeliveryResult result = adapter.send(CLAIM);

        assertThat(result).isInstanceOf(DeliveryResult.RetryableFailure.class);
        assertThat(((DeliveryResult.RetryableFailure) result).code()).isEqualTo("WEBHOOK_UNREACHABLE");
        assertThat(adapter.providerFor(result)).as("the attempt is a webhook attempt").isEqualTo("webhook");
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("F1 a DNS answer of no addresses is also retryable, not a permanent block")
    void emptyDnsAnswerIsRetryable() {
        endpointConfigured(true);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenThrow(new DestinationRejectedException(Reason.DNS_FAILURE,
                        "host receiver.example.com resolved to no address"));

        assertThat(adapter.send(CLAIM)).isInstanceOf(DeliveryResult.RetryableFailure.class);
    }

    @Test
    @DisplayName("F1 a blocked address stays PERMANENT: a private answer is a policy violation, not an outage")
    void blockedAddressIsPermanent() {
        endpointConfigured(true);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenThrow(new DestinationRejectedException(Reason.ADDRESS_BLOCKED,
                        "host receiver.example.com resolves into private 10.0.0.0/8"));

        DeliveryResult result = adapter.send(CLAIM);

        assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
        assertThat(((DeliveryResult.PermanentFailure) result).code()).isEqualTo("WEBHOOK_DESTINATION_BLOCKED");
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("F1 every other policy rejection also stays permanent")
    void otherRejectionsArePermanent() {
        endpointConfigured(true);
        for (Reason reason : List.of(Reason.SCHEME_NOT_ALLOWED, Reason.HOST_BLOCKED, Reason.HOST_IS_IP_LITERAL,
                Reason.PORT_NOT_ALLOWED, Reason.USERINFO_PRESENT, Reason.INVALID_SYNTAX)) {
            // doThrow, not when(...): re-stubbing with when() would call the already-stubbed mock and throw here.
            org.mockito.Mockito.doThrow(new DestinationRejectedException(reason, "rejected"))
                    .when(destinationResolver).resolve(anyString(), anyBoolean(), anyLong());

            assertThat(adapter.send(CLAIM)).as("%s", reason).isInstanceOf(DeliveryResult.PermanentFailure.class);
        }
    }

    @Test
    @DisplayName("F8 a refusal decided before any request is attributed to notify-system, not to the provider")
    void preSendRefusalsAreSystemAttributed() {
        when(endpointService.resolveDestinationForDelivery(TENANT)).thenReturn(Optional.empty());
        DeliveryResult missing = adapter.send(CLAIM);
        assertThat(((DeliveryResult.PermanentFailure) missing).code()).isEqualTo("WEBHOOK_ENDPOINT_MISSING");
        assertThat(adapter.providerFor(missing)).isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);

        endpointConfigured(false);
        DeliveryResult disabled = adapter.send(CLAIM);
        assertThat(((DeliveryResult.PermanentFailure) disabled).code()).isEqualTo("WEBHOOK_ENDPOINT_DISABLED");
        assertThat(adapter.providerFor(disabled)).isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);

        endpointConfigured(true);
        org.mockito.Mockito.doThrow(new DestinationRejectedException(Reason.ADDRESS_BLOCKED, "blocked"))
                .when(destinationResolver).resolve(anyString(), anyBoolean(), anyLong());
        DeliveryResult blocked = adapter.send(CLAIM);
        assertThat(adapter.providerFor(blocked)).isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);

        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("F8 a real send keeps the provider name, whatever its outcome")
    void sentAttemptsKeepTheProviderName() throws Exception {
        endpointConfigured(true);
        pinned();
        when(sender.send(any(), any())).thenReturn(WebhookSendOutcome.response(200, null, false, 12L));
        DeliveryResult delivered = adapter.send(CLAIM);
        assertThat(delivered).isInstanceOf(DeliveryResult.Success.class);
        assertThat(adapter.providerFor(delivered)).isEqualTo("webhook");

        when(sender.send(any(), any())).thenReturn(WebhookSendOutcome.response(503, null, false, 12L));
        DeliveryResult serverError = adapter.send(CLAIM);
        assertThat(adapter.providerFor(serverError)).isEqualTo("webhook");

        when(sender.send(any(), any())).thenReturn(WebhookSendOutcome.response(404, null, false, 12L));
        DeliveryResult clientError = adapter.send(CLAIM);
        assertThat(((DeliveryResult.PermanentFailure) clientError).code()).isEqualTo("WEBHOOK_CLIENT_ERROR");
        assertThat(adapter.providerFor(clientError))
                .as("the receiver answered, so this is the provider's own outcome").isEqualTo("webhook");
    }

    @Test
    @DisplayName("F2 the payload carries the immutable createdAt, not the queuedAt a retry release rewrites")
    void payloadUsesCreatedAt() throws Exception {
        endpointConfigured(true);
        pinned();
        when(sender.send(any(), any())).thenReturn(WebhookSendOutcome.response(200, null, false, 1L));

        adapter.send(CLAIM);

        var captor = org.mockito.ArgumentCaptor.forClass(WebhookSendRequest.class);
        verify(sender).send(captor.capture(), any());
        String body = new String(captor.getValue().body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"createdAt\":\"" + CLAIM.createdAt() + "\"");
        assertThat(body).doesNotContain(CLAIM.queuedAt().toString());
    }

    @Test
    @DisplayName("a guard refusal becomes an attempt-free Deferred attributed to notify-system")
    void guardRefusalIsDeferred() throws Exception {
        endpointConfigured(true);
        pinned();
        when(sender.send(any(), any())).thenThrow(new com.notify.delivery.resilience.ProviderDeferredException(
                com.notify.delivery.resilience.ProviderCallGuard.CIRCUIT_OPEN, Duration.ofSeconds(30)));

        DeliveryResult result = adapter.send(CLAIM);

        assertThat(result)
                .as("untranslated, the refusal would escape as a RuntimeException and be charged an attempt")
                .isInstanceOf(DeliveryResult.Deferred.class);
        DeliveryResult.Deferred deferred = (DeliveryResult.Deferred) result;
        assertThat(deferred.code())
                .isEqualTo(com.notify.delivery.resilience.ProviderCallGuard.CIRCUIT_OPEN);
        assertThat(deferred.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(adapter.providerFor(result))
                .as("no socket was opened, so this is the notify-system group with the destination refusals")
                .isEqualTo(DeliveryOrchestrator.SYSTEM_PROVIDER);
    }

    private void pinned() throws UnknownHostException {
        ValidatedUrl url = new ValidatedUrl(URI.create(URL), "receiver.example.com", 443, true, false);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenReturn(new PinnedDestination(url, List.of(InetAddress.getByName("93.184.216.34"))));
    }
}
