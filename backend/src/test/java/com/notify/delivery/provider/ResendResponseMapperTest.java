package com.notify.delivery.provider;

import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.resend.ResendResponseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** R1: complete Resend result mapping (plan section 10.2, ). */
class ResendResponseMapperTest {

    private enum Kind { SUCCESS, RETRYABLE, PERMANENT }

    private static String err(String name) {
        return "{\"statusCode\":0,\"name\":\"" + name + "\",\"message\":\"detail\"}";
    }

    static Stream<Arguments> table() {
        return Stream.of(
                // 2xx
                Arguments.of(200, "{\"id\":\"re_abc\"}", false, Kind.SUCCESS, "re_abc"),
                Arguments.of(201, "{\"id\":\"re_abc\"}", false, Kind.SUCCESS, "re_abc"),
                Arguments.of(200, "{}", false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, "{\"id\":\"\"}", false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, "{\"id\":7}", false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, "not json", false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, "[1,2]", false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, "", false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, null, false, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                Arguments.of(200, "{\"id\":\"re_abc\"}", true, Kind.RETRYABLE, "INVALID_PROVIDER_RESPONSE"),
                // 408 / 429 / 5xx
                Arguments.of(408, err("request_timeout"), false, Kind.RETRYABLE, "PROVIDER_TIMEOUT"),
                Arguments.of(429, err("rate_limit_exceeded"), false, Kind.RETRYABLE, "RATE_LIMITED"),
                Arguments.of(429, err("daily_quota_exceeded"), false, Kind.RETRYABLE, "RATE_LIMITED"),
                Arguments.of(429, err("monthly_quota_exceeded"), false, Kind.RETRYABLE, "RATE_LIMITED"),
                Arguments.of(429, null, false, Kind.RETRYABLE, "RATE_LIMITED"),
                Arguments.of(500, err("application_error"), false, Kind.RETRYABLE, "PROVIDER_UNAVAILABLE"),
                Arguments.of(502, null, false, Kind.RETRYABLE, "PROVIDER_UNAVAILABLE"),
                Arguments.of(503, "gateway", false, Kind.RETRYABLE, "PROVIDER_UNAVAILABLE"),
                Arguments.of(504, null, false, Kind.RETRYABLE, "PROVIDER_UNAVAILABLE"),
                Arguments.of(599, null, false, Kind.RETRYABLE, "PROVIDER_UNAVAILABLE"),
                // 409 ()
                Arguments.of(409, err("concurrent_idempotent_requests"), false, Kind.RETRYABLE, "CONCURRENT_IDEMPOTENCY_IN_PROGRESS"),
                Arguments.of(409, err("resource_locked"), false, Kind.RETRYABLE, "RESOURCE_LOCKED"),
                Arguments.of(409, err("invalid_idempotent_request"), false, Kind.PERMANENT, "IDEMPOTENCY_PAYLOAD_MISMATCH"),
                Arguments.of(409, err("something_else"), false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                Arguments.of(409, null, false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                // 400 / 422
                Arguments.of(400, err("validation_error"), false, Kind.PERMANENT, "VALIDATION_FAILED"),
                Arguments.of(422, err("missing_required_field"), false, Kind.PERMANENT, "VALIDATION_FAILED"),
                Arguments.of(422, err("invalid_parameter"), false, Kind.PERMANENT, "VALIDATION_FAILED"),
                Arguments.of(400, err("invalid_idempotency_key"), false, Kind.PERMANENT, "INVALID_IDEMPOTENCY_KEY"),
                Arguments.of(422, err("invalid_idempotency_key"), false, Kind.PERMANENT, "INVALID_IDEMPOTENCY_KEY"),
                // 401 / 403
                Arguments.of(403, err("validation_error"), false, Kind.PERMANENT, "SENDER_DOMAIN_UNVERIFIED"),
                Arguments.of(403, err("restricted_api_key"), false, Kind.PERMANENT, "AUTHENTICATION_FAILED"),
                Arguments.of(403, err("suspended_api_key"), false, Kind.PERMANENT, "AUTHENTICATION_FAILED"),
                Arguments.of(401, err("missing_api_key"), false, Kind.PERMANENT, "AUTHENTICATION_FAILED"),
                Arguments.of(401, err("validation_error"), false, Kind.PERMANENT, "AUTHENTICATION_FAILED"),
                Arguments.of(403, null, false, Kind.PERMANENT, "AUTHENTICATION_FAILED"),
                // other 4xx / unexpected 1xx 3xx (redirects are never followed)
                Arguments.of(404, err("not_found"), false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                Arguments.of(405, null, false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                Arguments.of(451, null, false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                Arguments.of(301, null, false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                Arguments.of(302, null, false, Kind.PERMANENT, "PROVIDER_CLIENT_ERROR"),
                // non-2xx with an oversize body is classified by status alone
                Arguments.of(500, "x", true, Kind.RETRYABLE, "PROVIDER_UNAVAILABLE"),
                Arguments.of(429, "x", true, Kind.RETRYABLE, "RATE_LIMITED"),
                Arguments.of(422, "x", true, Kind.PERMANENT, "VALIDATION_FAILED"),
                Arguments.of(401, "x", true, Kind.PERMANENT, "AUTHENTICATION_FAILED")
        );
    }

    @ParameterizedTest(name = "[{index}] HTTP {0} oversize={2} -> {3} {4}")
    @MethodSource("table")
    @DisplayName("R1 complete result mapping")
    void mapsEveryRow(int status, String body, boolean oversize, Kind kind, String expected) {
        DeliveryResult result = ResendResponseMapper.map(status, body, oversize);
        switch (kind) {
            case SUCCESS -> {
                assertThat(result).isInstanceOf(DeliveryResult.Success.class);
                assertThat(((DeliveryResult.Success) result).messageId()).isEqualTo(expected);
            }
            case RETRYABLE -> {
                assertThat(result).isInstanceOf(DeliveryResult.RetryableFailure.class);
                assertThat(((DeliveryResult.RetryableFailure) result).code()).isEqualTo(expected);
            }
            case PERMANENT -> {
                assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
                assertThat(((DeliveryResult.PermanentFailure) result).code()).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("R1 failure results record the HTTP status")
    void recordsHttpStatus() {
        assertThat(((DeliveryResult.RetryableFailure) ResendResponseMapper.map(503, null, false)).httpStatus()).isEqualTo(503);
        assertThat(((DeliveryResult.PermanentFailure) ResendResponseMapper.map(422, err("validation_error"), false)).httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("R1 a message id longer than the 64 character column is truncated, never rejected")
    void longMessageIdIsTruncated() {
        String id = "x".repeat(100);
        DeliveryResult result = ResendResponseMapper.map(200, "{\"id\":\"" + id + "\"}", false);
        assertThat(((DeliveryResult.Success) result).messageId()).hasSize(64);
    }

    @Test
    @DisplayName("R7 error text and snippets are bounded and email addresses are masked")
    void masksAddressesAndBoundsText() {
        String body = "{\"name\":\"validation_error\",\"message\":\"The to address person.name+tag@Example.COM is invalid\"}";
        DeliveryResult result = ResendResponseMapper.map(422, body, false);
        DeliveryResult.PermanentFailure failure = (DeliveryResult.PermanentFailure) result;
        assertThat(failure.message()).contains("[masked-email]").doesNotContain("person.name").doesNotContain("Example.COM");
        assertThat(failure.snippet()).contains("[masked-email]").doesNotContain("person.name");

        String huge = "{\"name\":\"application_error\",\"message\":\"" + "m".repeat(5_000) + "\"}";
        DeliveryResult.RetryableFailure big = (DeliveryResult.RetryableFailure) ResendResponseMapper.map(500, huge, false);
        assertThat(big.message().length()).isLessThanOrEqualTo(1024);
        assertThat(big.snippet().length()).isLessThanOrEqualTo(1024);
    }

    @Test
    @DisplayName("R1 an unparseable 2xx stores a masked, bounded snippet for diagnosis")
    void invalid2xxKeepsMaskedSnippet() {
        DeliveryResult.RetryableFailure result =
                (DeliveryResult.RetryableFailure) ResendResponseMapper.map(200, "<html>user@example.com</html>", false);
        assertThat(result.code()).isEqualTo("INVALID_PROVIDER_RESPONSE");
        assertThat(result.snippet()).contains("[masked-email]").doesNotContain("user@example.com");
    }
}
