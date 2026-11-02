package com.notify.delivery.provider.inapp;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.notification.dto.ClaimSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-app adapter must stay pure (/ , ).
 *
 * <p>This is the structural guard against repeating the defect repaired. {@code DeliveryOrchestrator} still
 * converts any exception escaping an adapter into a retryable {@code PROVIDER_UNAVAILABLE} outcome, which spends an
 * attempt of the notification's budget. An adapter that touched the database could therefore turn a database outage
 * into a fabricated provider failure. The in-app adapter holds no database collaborator at all, so that is
 * impossible by construction rather than by care — and this test fails if anyone adds one.
 */
class InAppAdapterPurityTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    @DisplayName("the adapter is constructible with no collaborators at all")
    void constructibleWithoutCollaborators() {
        assertThat(new InAppChannelAdapter().channel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    @DisplayName("the adapter declares no repository, EntityManager, JdbcTemplate or template-like field")
    void declaresNoDatabaseCollaborator() {
        for (Field field : InAppChannelAdapter.class.getDeclaredFields()) {
            String type = field.getType().getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(type)
                    .as("field %s of type %s", field.getName(), field.getType().getName())
                    .doesNotContain("repository")
                    .doesNotContain("entitymanager")
                    .doesNotContain("jdbc")
                    .doesNotContain("datasource")
                    .doesNotContain("redis")
                    .doesNotContain("transactiontemplate");
        }
    }

    @Test
    @DisplayName("send() returns Success with the notification id and performs no work of its own")
    void sendIsPure() {
        ClaimSnapshot claim = new ClaimSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), 0, 1, 0, 1, 5, 0,
                Channel.IN_APP, "user-42", "Subject", "Body", null, null, null,
                NOW.minusSeconds(60), NOW.minusSeconds(600));

        DeliveryResult result = new InAppChannelAdapter().send(claim);

        assertThat(result).isInstanceOf(DeliveryResult.Success.class);
        assertThat(((DeliveryResult.Success) result).messageId()).isEqualTo(claim.id().toString());
    }
}
