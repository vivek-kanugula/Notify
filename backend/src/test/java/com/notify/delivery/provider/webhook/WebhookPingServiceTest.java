package com.notify.delivery.provider.webhook;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.model.WebhookDestination;
import com.notify.webhook.security.DestinationRejectedException;
import com.notify.webhook.security.DestinationRejectedException.Reason;
import com.notify.webhook.security.DestinationResolver;
import com.notify.webhook.security.DestinationResolver.PinnedDestination;
import com.notify.webhook.security.WebhookUrlPolicy.ValidatedUrl;
import com.notify.webhook.service.WebhookEndpointService;
import com.notify.webhook.service.WebhookPingSender.PingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The test ping classifies destination outcomes the same way a real attempt does (F1), and still writes nothing.
 */
class WebhookPingServiceTest {

    private static final UUID TENANT = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String URL = "https://receiver.example.com/hook";

    private WebhookEndpointService endpointService;
    private DestinationResolver destinationResolver;
    private WebhookSender sender;
    private WebhookPingService ping;

    @BeforeEach
    void setUp() {
        endpointService = mock(WebhookEndpointService.class);
        destinationResolver = mock(DestinationResolver.class);
        sender = mock(WebhookSender.class);
        WebhookProperties properties = new WebhookProperties(
                "", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2), 8192, 4096, "Notify-Webhook/1", List.of());
        ping = new WebhookPingService(endpointService, destinationResolver, sender, properties,
                Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC));
    }

    private void endpoint(boolean enabled) {
        when(endpointService.resolveDestination(TENANT)).thenReturn(Optional.of(new WebhookDestination(
                UUID.randomUUID(), TENANT, URL, false, enabled, "whsec_" + "A".repeat(43))));
    }

    @Test
    @DisplayName("F1 an unresolvable host reports RETRYABLE WEBHOOK_UNREACHABLE, not a permanent block")
    void dnsFailureIsRetryable() {
        endpoint(true);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenThrow(new DestinationRejectedException(Reason.DNS_FAILURE, "could not be resolved"));

        PingResult result = ping.sendTestPing(TENANT);

        assertThat(result.delivered()).isFalse();
        assertThat(result.outcome()).isEqualTo("RETRYABLE");
        assertThat(result.errorCode()).isEqualTo("WEBHOOK_UNREACHABLE");
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("F1 a blocked address still reports PERMANENT WEBHOOK_DESTINATION_BLOCKED")
    void blockedAddressIsPermanent() {
        endpoint(true);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenThrow(new DestinationRejectedException(Reason.ADDRESS_BLOCKED, "resolves into private 10.0.0.0/8"));

        PingResult result = ping.sendTestPing(TENANT);

        assertThat(result.outcome()).isEqualTo("PERMANENT");
        assertThat(result.errorCode()).isEqualTo("WEBHOOK_DESTINATION_BLOCKED");
    }

    @Test
    @DisplayName("a disabled endpoint is refused before any request")
    void disabledEndpointIsRefused() {
        endpoint(false);

        PingResult result = ping.sendTestPing(TENANT);

        assertThat(result.errorCode()).isEqualTo("WEBHOOK_ENDPOINT_DISABLED");
        verify(sender, never()).send(any(), any());
        verify(destinationResolver, never()).resolve(anyString(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("no endpoint at all is NOT_FOUND")
    void missingEndpointIsNotFound() {
        when(endpointService.resolveDestination(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ping.sendTestPing(TENANT))
                .isInstanceOf(NotifyException.class)
                .extracting(e -> ((NotifyException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a 2xx ping reports DELIVERED and never returns a response body or a secret")
    void deliveredPingIsSanitized() throws Exception {
        endpoint(true);
        ValidatedUrl url = new ValidatedUrl(URI.create(URL), "receiver.example.com", 443, true, false);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenReturn(new PinnedDestination(url, List.of(InetAddress.getByName("93.184.216.34"))));
        when(sender.send(any(), any()))
                .thenReturn(WebhookSendOutcome.response(200, "secret-looking body", false, 11L));

        PingResult result = ping.sendTestPing(TENANT);

        assertThat(result.delivered()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.outcome()).isEqualTo("DELIVERED");
        assertThat(result.detail()).doesNotContain("secret-looking body");
        assertThat(result.toString()).doesNotContain("whsec_");

        var captor = org.mockito.ArgumentCaptor.forClass(WebhookSendRequest.class);
        verify(sender).send(captor.capture(), any());
        assertThat(captor.getValue().deliveryId()).startsWith("ping-");
        assertThat(captor.getValue().headers()).containsKey("X-Notify-Tenant");
        assertThat(captor.getValue().headers()).containsKey(WebhookSignature.HEADER);
    }

    @Test
    @DisplayName("the ping carries one budget covering resolution and the request")
    void pingPassesADeadline() throws Exception {
        endpoint(true);
        ValidatedUrl url = new ValidatedUrl(URI.create(URL), "receiver.example.com", 443, true, false);
        when(destinationResolver.resolve(anyString(), anyBoolean(), anyLong()))
                .thenReturn(new PinnedDestination(url, List.of(InetAddress.getByName("93.184.216.34"))));
        when(sender.send(any(), any())).thenReturn(WebhookSendOutcome.response(204, null, false, 3L));

        ping.sendTestPing(TENANT);

        var deadline = org.mockito.ArgumentCaptor.forClass(WebhookDeadline.class);
        verify(sender).send(any(), deadline.capture());
        assertThat(deadline.getValue()).isNotNull();
        assertThat(deadline.getValue().remainingMs()).isPositive();
    }
}
