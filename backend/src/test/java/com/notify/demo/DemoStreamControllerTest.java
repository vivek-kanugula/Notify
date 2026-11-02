package com.notify.demo;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.demo.config.DemoProperties;
import com.notify.demo.controller.DemoStreamController;
import com.notify.demo.dto.DemoInboxItemResponse;
import com.notify.demo.service.DemoInboxService;
import com.notify.demo.service.DemoStreamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoStreamControllerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("stream returns 404 when demo surface is disabled")
    void streamRefusedWhenDisabled() {
        DemoProperties props = new DemoProperties("", false, List.of());
        DemoStreamService streamService = new DemoStreamService(props, clock);
        DemoStreamController controller = new DemoStreamController(props, streamService);

        assertThatThrownBy(controller::stream)
                .isInstanceOf(NotifyException.class)
                .satisfies(e -> {
                    NotifyException ne = (NotifyException) e;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("stream returns 404 when tenant slugs are empty despite enabled flag")
    void streamRefusedWhenNoSlugs() {
        DemoProperties props = new DemoProperties("", true, List.of());
        DemoStreamService streamService = new DemoStreamService(props, clock);
        DemoStreamController controller = new DemoStreamController(props, streamService);

        assertThatThrownBy(controller::stream)
                .isInstanceOf(NotifyException.class)
                .satisfies(e -> {
                    NotifyException ne = (NotifyException) e;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("stream returns active SseEmitter when enabled with demo tenant slugs")
    void streamSubscribesWhenEnabled() {
        DemoProperties props = new DemoProperties("", true, List.of("acme", "globex"));
        DemoStreamService streamService = new DemoStreamService(props, clock);
        DemoStreamController controller = new DemoStreamController(props, streamService);

        SseEmitter emitter = controller.stream();
        assertThat(emitter).isNotNull();
        assertThat(streamService.activeSubscriberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("recipient masking masks email and url recipients securely")
    void maskingEnforced() {
        assertThat(DemoInboxService.maskRecipient("john.doe@example.com")).isEqualTo("j***@example.com");
        assertThat(DemoInboxService.maskRecipient("a@domain.com")).isEqualTo("a***@domain.com");
        assertThat(DemoInboxService.maskRecipient("https://webhook.site/test")).startsWith("h***");
        assertThat(DemoInboxService.maskRecipient("")).isEqualTo("***");
        assertThat(DemoInboxService.maskRecipient(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("subject sanitization strips HTML tags and limits length")
    void sanitizationEnforced() {
        assertThat(DemoInboxService.sanitizeSubject("<script>alert(1)</script>Hello!"))
                .doesNotContain("<")
                .doesNotContain(">");
        assertThat(DemoInboxService.sanitizeSubject(null)).isNull();

        String longSubject = "A".repeat(100);
        assertThat(DemoInboxService.sanitizeSubject(longSubject).length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("simulationPulse generates compliant events when subscribers exist")
    void simulationPulseEmitsWhenActive() {
        DemoProperties props = new DemoProperties("", true, List.of("acme", "globex"));
        DemoStreamService streamService = new DemoStreamService(props, clock);

        // Before subscriber: pulse is a no-op
        streamService.simulationPulse();
        assertThat(streamService.activeSubscriberCount()).isEqualTo(0);

        // Subscribe an emitter
        SseEmitter emitter = streamService.subscribe();
        assertThat(streamService.activeSubscriberCount()).isEqualTo(1);

        // Trigger pulse
        streamService.simulationPulse();
        // Emitter remains active and healthy
        assertThat(streamService.activeSubscriberCount()).isEqualTo(1);

        // Trigger heartbeat
        streamService.sendHeartbeat();
        assertThat(streamService.activeSubscriberCount()).isEqualTo(1);
    }
}
