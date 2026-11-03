package com.notify.delivery.provider.email;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailChannelAdapterTest {

    @Test
    @DisplayName("formatFromName applies 'Tenant via Notify' display name")
    void formatsFromNameWithTenantName() {
        String result = EmailChannelAdapter.formatFromName("Acme Corp");
        assertThat(result).isEqualTo("Acme Corp via Notify");
    }

    @Test
    @DisplayName("formatFromName returns null when tenant name is missing or blank")
    void formatFromNameReturnsNullWhenBlank() {
        assertThat(EmailChannelAdapter.formatFromName(null)).isNull();
        assertThat(EmailChannelAdapter.formatFromName("   ")).isNull();
    }

    @Test
    @DisplayName("formatFromName sanitizes header injection characters")
    void sanitizesFromNameHeaderInjection() {
        String result = EmailChannelAdapter.formatFromName("Acme\r\nBcc: evil@test.com");
        assertThat(result).doesNotContain("\r").doesNotContain("\n");
        assertThat(result).isEqualTo("AcmeBcc: evil@test.com via Notify");
    }

    @Test
    @DisplayName("formatSender applies 'Tenant via Notify' display name to raw email")
    void formatsSenderWithTenantName() {
        String result = EmailChannelAdapter.formatSender("notifications@mail.vivekstack.dev", "Acme Corp");
        assertThat(result).isEqualTo("\"Acme Corp via Notify\" <notifications@mail.vivekstack.dev>");
    }

    @Test
    @DisplayName("formatSender extracts email if sender was already formatted")
    void formatsSenderExtractingFromPreformatted() {
        String result = EmailChannelAdapter.formatSender("Notify <notifications@mail.vivekstack.dev>", "Stripe");
        assertThat(result).isEqualTo("\"Stripe via Notify\" <notifications@mail.vivekstack.dev>");
    }

    @Test
    @DisplayName("formatSender preserves configured sender if tenant name is missing or blank")
    void preservesSenderWhenNoTenantName() {
        assertThat(EmailChannelAdapter.formatSender("notifications@mail.vivekstack.dev", null))
                .isEqualTo("notifications@mail.vivekstack.dev");
        assertThat(EmailChannelAdapter.formatSender("notifications@mail.vivekstack.dev", "   "))
                .isEqualTo("notifications@mail.vivekstack.dev");
    }

    @Test
    @DisplayName("formatSender sanitizes header injection characters")
    void sanitizesHeaderInjection() {
        String result = EmailChannelAdapter.formatSender("notifications@mail.vivekstack.dev", "Acme\r\nBcc: evil@test.com");
        assertThat(result).doesNotContain("\r").doesNotContain("\n");
        assertThat(result).isEqualTo("\"AcmeBcc: evil@test.com via Notify\" <notifications@mail.vivekstack.dev>");
    }

    @Test
    @DisplayName("extractReplyTo retrieves replyTo from metadata JSON")
    void extractsReplyToFromMetadata() {
        assertThat(EmailChannelAdapter.extractReplyTo("{\"replyTo\":\"support@acme.com\"}")).isEqualTo("support@acme.com");
        assertThat(EmailChannelAdapter.extractReplyTo("{\"other\":\"val\"}")).isNull();
        assertThat(EmailChannelAdapter.extractReplyTo(null)).isNull();
        assertThat(EmailChannelAdapter.extractReplyTo("not valid json")).isNull();
    }

    @Test
    @DisplayName("send() passes formatted sender and replyTo to provider")
    void sendBuildsRequestWithFriendlyNameAndReplyTo() {
        UUID tenantId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();

        TenantRepository tenantRepo = mock(TenantRepository.class);
        TenantEntity tenant = new TenantEntity("Acme Corp", "acme-corp");
        tenant.setId(tenantId);
        when(tenantRepo.findById(tenantId)).thenReturn(Optional.of(tenant));

        AtomicReference<EmailSendRequest> captured = new AtomicReference<>();
        EmailProvider provider = new EmailProvider() {
            @Override
            public String name() {
                return "test-provider";
            }

            @Override
            public DeliveryResult send(EmailSendRequest request) {
                captured.set(request);
                return new DeliveryResult.Success("msg-123");
            }
        };

        EmailChannelAdapter adapter = new EmailChannelAdapter(provider, tenantRepo);

        ClaimSnapshot claim = new ClaimSnapshot(
                notificationId,
                tenantId,
                1, 1, 1, 1, 5, 0,
                Channel.EMAIL,
                "customer@example.com",
                "Monthly Invoice",
                "Here is your invoice",
                "{\"replyTo\":\"billing@acme.com\"}",
                "trace-1",
                "notifications@mail.vivekstack.dev",
                Instant.now(),
                Instant.now()
        );

        DeliveryResult result = adapter.send(claim);

        assertThat(result).isInstanceOf(DeliveryResult.Success.class);
        EmailSendRequest sent = captured.get();
        assertThat(sent).isNotNull();
        assertThat(sent.from()).isEqualTo("notifications@mail.vivekstack.dev");
        assertThat(sent.fromName()).isEqualTo("Acme Corp via Notify");
        assertThat(sent.to()).isEqualTo("customer@example.com");
        assertThat(sent.replyTo()).isEqualTo("billing@acme.com");
    }
}

