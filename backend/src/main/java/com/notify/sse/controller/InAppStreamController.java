package com.notify.sse.controller;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.sse.stream.SseStreamRegistry;
import com.notify.sse.token.StreamToken;
import com.notify.sse.token.StreamTokenService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

/**
 * The in-app SSE stream (CONTRACTS §SSE; / , , , ).
 *
 * <p><b>Why this path is outside {@code /api/v1}.</b> `SecurityConfig` requires a JWT or an API key there, and an
 * `EventSource` can send neither — it cannot set request headers at all. Authorization is therefore the single-use
 * token, consumed atomically on connect: the loser of a race, a replayed token, an expired token and an unverifiable
 * token all fail closed with {@code 401 STREAM_TOKEN_INVALID}.
 *
 * <p>The tenant and recipient come from the <b>consumed token</b>, never from a query parameter, so a client cannot
 * aim a stream at another tenant or another recipient by editing the URL.
 */
@RestController
@RequestMapping("/sse")
public class InAppStreamController {

    private final StreamTokenService tokens;
    private final SseStreamRegistry registry;

    public InAppStreamController(StreamTokenService tokens, SseStreamRegistry registry) {
        this.tokens = tokens;
        this.registry = registry;
    }

    /**
     * Opens the stream, or answers the refusal.
     *
     * <p><b>No {@code produces} on this mapping, deliberately (corrective cycle).</b> It used to declare
     * {@code text/event-stream}, which made the refusal branch unwritable: a problem body is a {@code Map}, no
     * converter produces {@code text/event-stream} from one, and the request therefore answered <b>500</b> instead
     * of the documented {@code 429} with a {@code Retry-After}. Nothing caught it, because no test had ever asserted
     * the status of an opened or refused stream and the dev server did not proxy {@code /sse} at all. The success
     * branch needs no declaration either: {@link SseEmitter} sets {@code text/event-stream} on the response itself.
     * The refusal sets {@code application/problem+json} explicitly, so an {@code EventSource}'s
     * {@code Accept: text/event-stream} cannot turn it into a 406.
     */
    @GetMapping("/inapp")
    public Object stream(
            @RequestParam("token") String token,
            @RequestParam(value = "lastEventId", required = false) String lastEventId
    ) {
        // Validate the cursor BEFORE consuming the token, so a client's malformed retry does not also burn its token.
        long cursor = lastEventId == null || lastEventId.isBlank()
                ? 0L : InAppStreamSupport.parseCursor(lastEventId, "lastEventId");

        Optional<StreamToken> consumed = tokens.consume(token);
        if (consumed.isEmpty()) {
            throw new NotifyException(ErrorCode.STREAM_TOKEN_INVALID,
                    "The stream token is invalid, already used, or expired");
        }
        StreamToken binding = consumed.get();

        Object opened = registry.open(binding.tenantId(), binding.recipientRef(), cursor);
        if (opened instanceof SseStreamRegistry.Refusal refusal) {
            long retryAfterSeconds = Math.max(1, refusal.retryAfterMillis() / 1000);
            return ResponseEntity.status(ErrorCode.STREAM_LIMIT_REACHED.getDefaultStatus())
                    .header("Retry-After", Long.toString(retryAfterSeconds))
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(Map.of(
                            "type", "https://notify.dev/errors/stream-limit-reached",
                            "title", ErrorCode.STREAM_LIMIT_REACHED.name(),
                            "status", ErrorCode.STREAM_LIMIT_REACHED.getDefaultStatus().value(),
                            "detail", "No stream slot is available for this tenant (" + refusal.reason() + ")",
                            "retryAfterSeconds", retryAfterSeconds));
        }
        return (SseEmitter) opened;
    }
}
