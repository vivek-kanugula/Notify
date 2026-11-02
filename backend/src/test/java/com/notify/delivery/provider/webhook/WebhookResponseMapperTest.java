package com.notify.delivery.provider.webhook;

import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.webhook.WebhookSendOutcome.Failure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * , exhaustively. The retry decision is made here, so a wrong class here means either a permanently lost
 * notification or an endless retry of something that will never succeed.
 */
class WebhookResponseMapperTest {

    private static DeliveryResult mapStatus(int status) {
        return WebhookResponseMapper.map(WebhookSendOutcome.response(status, null, false, 12L));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204, 299})
    @DisplayName("any 2xx is success, and carries no provider message id")
    void successRange(int status) {
        DeliveryResult result = mapStatus(status);

        assertThat(result).isInstanceOf(DeliveryResult.Success.class);
        assertThat(((DeliveryResult.Success) result).messageId()).isNull();
    }

    @Test
    @DisplayName("a 2xx is success even when the body was oversize and truncated: only the status confirms acceptance")
    void successIgnoresBody() {
        DeliveryResult result = WebhookResponseMapper.map(
                WebhookSendOutcome.response(200, "not json at all", true, 9L));

        assertThat(result).isInstanceOf(DeliveryResult.Success.class);
    }

    @ParameterizedTest
    @CsvSource({
            "408, WEBHOOK_TIMEOUT",
            "429, WEBHOOK_RATE_LIMITED",
            "500, WEBHOOK_SERVER_ERROR",
            "502, WEBHOOK_SERVER_ERROR",
            "503, WEBHOOK_SERVER_ERROR",
            "504, WEBHOOK_SERVER_ERROR",
            "599, WEBHOOK_SERVER_ERROR"
    })
    @DisplayName("408, 429 and 5xx are retryable with their own codes")
    void retryableStatuses(int status, String code) {
        DeliveryResult result = mapStatus(status);

        assertThat(result).isInstanceOf(DeliveryResult.RetryableFailure.class);
        DeliveryResult.RetryableFailure failure = (DeliveryResult.RetryableFailure) result;
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.httpStatus()).isEqualTo(status);
        assertThat(failure.message()).contains(String.valueOf(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 410, 413, 422, 451, 499})
    @DisplayName("every other 4xx is permanent: retrying a receiver that rejects the request cannot help")
    void permanentClientErrors(int status) {
        DeliveryResult result = mapStatus(status);

        assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
        DeliveryResult.PermanentFailure failure = (DeliveryResult.PermanentFailure) result;
        assertThat(failure.code()).isEqualTo("WEBHOOK_CLIENT_ERROR");
        assertThat(failure.httpStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    @DisplayName("a 3xx is permanent and says redirects are never followed (the SSRF reason)")
    void redirectsArePermanent(int status) {
        DeliveryResult result = mapStatus(status);

        assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
        DeliveryResult.PermanentFailure failure = (DeliveryResult.PermanentFailure) result;
        assertThat(failure.code()).isEqualTo("WEBHOOK_REDIRECT_NOT_ALLOWED");
        assertThat(failure.message()).contains("redirects are never followed");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100, 199, 600, 999})
    @DisplayName("a status outside the classified ranges is a permanent protocol error, never a silent success")
    void unexpectedStatus(int status) {
        DeliveryResult result = mapStatus(status);

        assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
        assertThat(((DeliveryResult.PermanentFailure) result).code()).isEqualTo("WEBHOOK_PROTOCOL_ERROR");
    }

    @ParameterizedTest
    @CsvSource({
            "TIMEOUT, WEBHOOK_TIMEOUT",
            "UNREACHABLE, WEBHOOK_UNREACHABLE",
            "TLS, WEBHOOK_TLS_FAILURE"
    })
    @DisplayName("timeout, unreachable and TLS failures are retryable: the receiver may be back on the next attempt")
    void retryableTransportFailures(Failure failure, String code) {
        DeliveryResult result = WebhookResponseMapper.map(WebhookSendOutcome.failure(failure, null, 2_000L));

        assertThat(result).isInstanceOf(DeliveryResult.RetryableFailure.class);
        DeliveryResult.RetryableFailure mapped = (DeliveryResult.RetryableFailure) result;
        assertThat(mapped.code()).isEqualTo(code);
        assertThat(mapped.httpStatus()).isNull();
        assertThat(mapped.message()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
            "PROTOCOL, WEBHOOK_PROTOCOL_ERROR",
            "BLOCKED, WEBHOOK_DESTINATION_BLOCKED"
    })
    @DisplayName("an unparsable response and a blocked destination are permanent, so neither is retried")
    void permanentTransportFailures(Failure failure, String code) {
        DeliveryResult result = WebhookResponseMapper.map(WebhookSendOutcome.failure(failure, null, 5L));

        assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
        assertThat(((DeliveryResult.PermanentFailure) result).code()).isEqualTo(code);
    }

    @Test
    @DisplayName("a transport detail is used when present and a safe fallback message otherwise")
    void transportDetail() {
        DeliveryResult withDetail = WebhookResponseMapper.map(
                WebhookSendOutcome.failure(Failure.UNREACHABLE, "Connection refused", 7L));
        assertThat(((DeliveryResult.RetryableFailure) withDetail).message()).isEqualTo("Connection refused");

        DeliveryResult blankDetail = WebhookResponseMapper.map(
                WebhookSendOutcome.failure(Failure.UNREACHABLE, "   ", 7L));
        assertThat(((DeliveryResult.RetryableFailure) blankDetail).message())
                .isEqualTo("Receiver could not be reached");
    }

    @Test
    @DisplayName("the snippet is passed through exactly as the sender bounded it, marker included")
    void snippetIsPassedThroughUnchanged() {
        // F6: the sender owns the stored budget and the truncation marker, so the mapper must not re-decorate the
        // value — a second marker, or one appended past the budget, would be cut off again on the way to the column.
        // That the marker exists and survives the write is asserted where it is produced and where it is stored:
        // WebhookSenderTest.oversizeBodyIsBounded / multibyteBodyIsBoundedByBytes and
        // WebhookWorkerIntegrationTest.oversizeFailureSnippetIsStoredWithItsMarker.
        DeliveryResult truncated = WebhookResponseMapper.map(
                WebhookSendOutcome.response(500, "boom" + WebhookSender.TRUNCATION_MARKER, true, 11L));
        assertThat(((DeliveryResult.RetryableFailure) truncated).snippet())
                .isEqualTo("boom" + WebhookSender.TRUNCATION_MARKER);

        DeliveryResult full = WebhookResponseMapper.map(WebhookSendOutcome.response(500, "boom", false, 11L));
        assertThat(((DeliveryResult.RetryableFailure) full).snippet()).isEqualTo("boom");

        DeliveryResult none = WebhookResponseMapper.map(WebhookSendOutcome.response(500, null, false, 11L));
        assertThat(((DeliveryResult.RetryableFailure) none).snippet()).isNull();
    }

    @Test
    @DisplayName("a Retry-After the receiver sent is recorded in the attempt message, and absence changes nothing")
    void retryAfterIsRecordedInTheMessage() {
        DeliveryResult with = WebhookResponseMapper.map(
                WebhookSendOutcome.response(503, null, false, 7L, "120"));
        assertThat(((DeliveryResult.RetryableFailure) with).message())
                .isEqualTo("Receiver answered 503 (Retry-After: 120)");

        DeliveryResult without = WebhookResponseMapper.map(WebhookSendOutcome.response(503, null, false, 7L));
        assertThat(((DeliveryResult.RetryableFailure) without).message()).isEqualTo("Receiver answered 503");
    }

    @Test
    @DisplayName("the provider name recorded on every webhook attempt is stable")
    void providerName() {
        assertThat(WebhookResponseMapper.PROVIDER_NAME).isEqualTo("webhook");
    }
}
