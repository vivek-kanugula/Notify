package com.notify.sse.controller;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.sse.config.SseProperties;
import com.notify.sse.stream.MonotonicTimeSource;
import com.notify.sse.stream.SseStreamRegistry;
import com.notify.sse.token.StreamToken;
import com.notify.sse.token.StreamTokenService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code GET /sse/inapp} answers when it cannot open a stream (corrective cycle).
 *
 * <p><b>What was wrong.</b> The mapping declared {@code produces = text/event-stream}. The refusal branch answers
 * with a problem body, which is a {@code Map}, and no message converter can write a {@code Map} as
 * {@code text/event-stream} - so a refused stream answered <b>500</b> instead of the documented
 * {@code 429 STREAM_LIMIT_REACHED} with a {@code Retry-After}. It survived the whole of because no test had ever
 * asserted the status of an opened or a refused stream: the delivery suite's only {@code /sse} cases assert the
 * <em>401</em> for a replayed or unknown token, and the browser suite could not reach the endpoint at all because
 * the dev server did not proxy {@code /sse}. A brand-new tenant always hits the ≈95 s provenance barrier on its
 * first admission, so the refusal branch is the <em>common</em> case, not an edge.
 */
class InAppStreamControllerRefusalTest {

    private static SseProperties props() {
        return new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 8);
    }

    /** A registry that always refuses, with the barrier's own retry hint. */
    private static SseStreamRegistry refusingRegistry(long retryAfterMillis) {
        return new SseStreamRegistry(null, null, null, props(), new MonotonicTimeSource(),
                new SimpleMeterRegistry(), Executors.newScheduledThreadPool(1), Runnable::run, Runnable::run) {
            @Override
            public Object open(UUID tenantId, String recipientRef, long lastEventId) {
                return new Refusal("BARRIER_ARMED_UNKNOWN_PROVENANCE", retryAfterMillis);
            }
        };
    }

    private static StreamTokenService consumingTokens(UUID tenantId, String recipientRef) {
        return new StreamTokenService(null, props()) {
            @Override
            public Optional<StreamToken> consume(String token) {
                return "good-token".equals(token)
                        ? Optional.of(new StreamToken(tenantId, recipientRef))
                        : Optional.empty();
            }
        };
    }

    @Test
    @DisplayName("a refused stream is 429 with Retry-After and a problem body, never 500")
    void aRefusalIsNotAServerError() {
        UUID tenantId = UUID.randomUUID();
        InAppStreamController controller =
                new InAppStreamController(consumingTokens(tenantId, "user-1"), refusingRegistry(95_000));

        Object answer = controller.stream("good-token", null);

        assertThat(answer).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) answer;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("95");
        // The content type is explicit, so an EventSource's `Accept: text/event-stream` cannot make this a 406.
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("title", ErrorCode.STREAM_LIMIT_REACHED.name());
        assertThat(response.getBody()).containsEntry("retryAfterSeconds", 95L);
        assertThat(String.valueOf(response.getBody().get("detail")))
                .contains("BARRIER_ARMED_UNKNOWN_PROVENANCE");
    }

    @Test
    @DisplayName("the retry hint is never below one second, so a client cannot be told to retry immediately")
    void theRetryHintIsFloored() {
        UUID tenantId = UUID.randomUUID();
        InAppStreamController controller =
                new InAppStreamController(consumingTokens(tenantId, "user-1"), refusingRegistry(200));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) controller.stream("good-token", null);

        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("an invalid cursor is 422 before the token is consumed, so a malformed retry does not burn it")
    void anInvalidCursorDoesNotBurnTheToken() {
        UUID tenantId = UUID.randomUUID();
        InAppStreamController controller =
                new InAppStreamController(consumingTokens(tenantId, "user-1"), refusingRegistry(95_000));

        assertThatThrownBy(() -> controller.stream("good-token", "not-a-number"))
                .isInstanceOf(NotifyException.class)
                .hasMessageContaining("lastEventId");
    }

    @Test
    @DisplayName("an unknown or already-used token is 401 STREAM_TOKEN_INVALID")
    void anUnknownTokenFailsClosed() {
        InAppStreamController controller =
                new InAppStreamController(consumingTokens(UUID.randomUUID(), "user-1"), refusingRegistry(95_000));

        assertThatThrownBy(() -> controller.stream("replayed", null))
                .isInstanceOf(NotifyException.class)
                .hasMessageContaining("invalid, already used, or expired");
    }
}
