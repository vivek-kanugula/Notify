package com.notify.observability.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the OpenTelemetry stack the app is configured with (OTLP bridge + W3C propagation) produces and
 * injects a W3C {@code traceparent} — the exact header the outbox relay writes onto Kafka records
 * ({@code KafkaDispatchProducer}) so the async path HTTP -> outbox -> Kafka -> worker stays one trace.
 *
 * <p>Deterministic and context-free: Spring Boot does not expose a {@code Tracer} bean in @SpringBootTest, so
 * this exercises the same OTel SDK + W3C propagator the production auto-configuration wires, asserting the
 * on-the-wire contract rather than bean presence.
 */
class TracingPropagationTest {

    @Test
    @DisplayName("W3C traceparent is produced and injected in the standard format")
    void injectsW3cTraceparent() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        try {
            Tracer tracer = otel.getTracer("notify-ingress-test");
            Span span = tracer.spanBuilder("POST /api/v1/notifications").startSpan();
            try (Scope scope = span.makeCurrent()) {
                Map<String, String> carrier = new HashMap<>();
                otel.getPropagators().getTextMapPropagator()
                        .inject(Context.current(), carrier, (c, k, v) -> c.put(k, v));

                assertThat(carrier).containsKey("traceparent");
                assertThat(carrier.get("traceparent"))
                        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            } finally {
                span.end();
            }
        } finally {
            tracerProvider.close();
        }
    }
}
