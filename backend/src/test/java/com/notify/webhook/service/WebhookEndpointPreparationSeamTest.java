package com.notify.webhook.service;

import com.notify.delivery.provider.ChannelPreparationException;
import com.notify.demo.config.DemoProperties;
import com.notify.webhook.entity.WebhookEndpointEntity;
import com.notify.webhook.repository.WebhookEndpointRepository;
import com.notify.webhook.security.WebhookUrlPolicy;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exactly where the preparation boundary sits (/ database-recovery repair).
 *
 * <p>The boundary surrounds the database access and nothing else. That placement is the whole design, and these are
 * the two assertions that pin it:
 * <ul>
 *   <li>a failure of the row read becomes a {@link ChannelPreparationException}, so the worker classifies it; and</li>
 *   <li>a failure to <em>decrypt</em> the stored secret does not, because a malformed or unauthenticated stored
 *       secret is one tenant's data fault, not infrastructure. Turning it into a preparation failure would classify
 *       it {@code UNCLASSIFIED} and stop the webhook worker for every tenant — a far worse outcome than failing the
 *       one notification, which is what it does today and still does.</li>
 * </ul>
 *
 * <p>The request-driven {@code resolveDestination} keeps its old behaviour untouched: the ping endpoint and the demo
 * receiver answer a request, so a data-access failure there is an ordinary 500, not a worker-recovery event.
 */
class WebhookEndpointPreparationSeamTest {

    private static final UUID TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private WebhookEndpointRepository repository;
    private WebhookSecretCipher cipher;
    private DefaultWebhookEndpointService service;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookEndpointRepository.class);
        cipher = mock(WebhookSecretCipher.class);
        // DemoProperties is a record, so it is built, not mocked; nothing here reaches provisioning anyway.
        service = new DefaultWebhookEndpointService(repository, cipher, mock(WebhookSecretGenerator.class),
                mock(WebhookUrlPolicy.class), new DemoProperties(""), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JDBCConnectionException connectionLost() {
        return new JDBCConnectionException("could not obtain connection",
                new SQLException("connection refused", "08006"));
    }

    private static WebhookEndpointEntity row() {
        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setUrl("https://receiver.example.com/hook");
        entity.setDemo(false);
        entity.setEnabled(true);
        entity.setSigningSecretEnc("not-a-valid-ciphertext");
        return entity;
    }

    @Test
    @DisplayName("the delivery path turns a failed row read into a preparation failure, cause preserved")
    void readFailureBecomesAPreparationFailure() {
        when(repository.findByTenantId(TENANT)).thenThrow(connectionLost());

        assertThatThrownBy(() -> service.resolveDestinationForDelivery(TENANT))
                .isInstanceOf(ChannelPreparationException.class)
                .hasCauseInstanceOf(JDBCConnectionException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("a decryption failure is not a preparation failure: it stays this notification's problem, not the worker's")
    void decryptionFailureIsNotAPreparationFailure() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.of(row()));
        when(cipher.decrypt(any(), any()))
                .thenThrow(new IllegalStateException("Stored webhook secret failed authentication: AEADBadTagException"));

        assertThatThrownBy(() -> service.resolveDestinationForDelivery(TENANT))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ChannelPreparationException.class);
    }

    @Test
    @DisplayName("the request-driven resolveDestination is unchanged: a failed read propagates as the data-access exception it is")
    void requestPathKeepsItsOldBehaviour() {
        when(repository.findByTenantId(TENANT)).thenThrow(connectionLost());

        assertThatThrownBy(() -> service.resolveDestination(TENANT))
                .isInstanceOf(JDBCConnectionException.class)
                .isNotInstanceOf(ChannelPreparationException.class);
    }

    @Test
    @DisplayName("a successful preparation is identical on both paths, decrypted secret included")
    void successfulPreparationMatchesTheRequestPath() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.of(row()));
        when(cipher.decrypt(any(), any())).thenReturn("whsec_" + "A".repeat(43));

        assertThat(service.resolveDestinationForDelivery(TENANT))
                .isEqualTo(service.resolveDestination(TENANT))
                .get()
                .satisfies(destination -> {
                    assertThat(destination.tenantId()).isEqualTo(TENANT);
                    assertThat(destination.url()).isEqualTo("https://receiver.example.com/hook");
                    assertThat(destination.enabled()).isTrue();
                    assertThat(destination.secret()).startsWith("whsec_");
                });
    }

    @Test
    @DisplayName("an unconfigured tenant is empty on the delivery path too, not a preparation failure")
    void unconfiguredTenantIsEmpty() {
        when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());

        assertThat(service.resolveDestinationForDelivery(TENANT)).isEmpty();
    }
}
