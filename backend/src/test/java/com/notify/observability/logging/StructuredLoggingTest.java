package com.notify.observability.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** / MDC carries tenantId/channel for structured logs; emails are masked, bodies absent. */
class StructuredLoggingTest {

    @Test
    @DisplayName("email masking reduces the local part; non-emails become ***")
    void emailMasking() {
        assertThat(LogMasking.maskEmail("john@acme.com")).isEqualTo("j***@acme.com");
        assertThat(LogMasking.maskEmail("not-an-email")).isEqualTo("***");
        assertThat(LogMasking.maskEmail(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("tenantId and channel propagate via MDC into log events")
    void mdcFieldsOnLogEvents() {
        Logger logger = (Logger) LoggerFactory.getLogger(StructuredLoggingTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MDC.put("tenantId", "tenant-123");
            MDC.put("channel", "EMAIL");
            logger.info("Delivering to {}", LogMasking.maskEmail("jane@acme.com"));
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap()).containsEntry("tenantId", "tenant-123");
        assertThat(event.getMDCPropertyMap()).containsEntry("channel", "EMAIL");
        assertThat(event.getFormattedMessage()).contains("j***@acme.com").doesNotContain("jane@acme.com");
    }
}
