package com.notify.dlq.consumer;

import org.springframework.test.context.TestPropertySource;

/**
 * Refusal cause 1: the configured initialization input cannot be read (/ ; ).
 *
 * <p>A configured-but-unreadable input is the operational case where a file was moved, mistyped or not mounted. It
 * must not degrade into "no input configured", which would start the listeners.
 */
@TestPropertySource(properties = {
        "notify.dlq.consumer.enabled=true",
        "notify.dlq.dlt-consumer.enabled=true",
        "notify.dlq.consumer.group-id=n17-refusal-dlq",
        "notify.dlq.dlt-consumer.group-id=n17-refusal-dlt",
        "notify.dlq.offset-init-file=${java.io.tmpdir}/n17-offset-input-that-does-not-exist.json",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.recovery.enabled=false",
        "notify.reconciler.enabled=false",
        "notify.outbox.relay-enabled=false"
})
class DlqStartupRefusalContextTest extends AbstractDlqStartupRefusalTest {

    @Override
    String expectedReasonFragment() {
        return "is not readable";
    }
}
