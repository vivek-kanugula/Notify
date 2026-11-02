package com.notify.dlq.consumer;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Refusal cause 2: the input parses but names another environment and another group (/ ; ).
 *
 * <p>This is the input-prepared-for-the-wrong-place case. It reaches further into the composition than an
 * unreadable file does - the file is read, parsed and validated, an admin client is created - and it must still
 * leave both listeners stopped with nothing committed.
 */
@TestPropertySource(properties = {
        "notify.dlq.consumer.enabled=true",
        "notify.dlq.dlt-consumer.enabled=true",
        "notify.dlq.consumer.group-id=n17-refusal-dlq",
        "notify.dlq.dlt-consumer.group-id=n17-refusal-dlt",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false",
        "notify.recovery.enabled=false",
        "notify.reconciler.enabled=false",
        "notify.outbox.relay-enabled=false"
})
class DlqStartupMismatchedInputContextTest extends AbstractDlqStartupRefusalTest {

    private static Path input;

    static {
        try {
            input = Files.createTempFile("n17-mismatched-offsets-", ".json");
            Files.writeString(input, "{\"environment\":\"staging\",\"groupId\":\"some-other-group\","
                    + "\"offsets\":[{\"topic\":\"notify.dlq.v1\",\"partition\":0,\"nextOffset\":0}]}");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void mismatchedInput(DynamicPropertyRegistry registry) {
        registry.add("notify.dlq.offset-init-file", () -> input.toString());
    }

    @Override
    String expectedReasonFragment() {
        // The group check fires first for a listener whose group the input does not name.
        return "which is not the group of listener";
    }
}
