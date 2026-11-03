package com.notify.delivery.provider.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.DeliveryChannelAdapter;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.resilience.ProviderCallGuard;
import com.notify.delivery.resilience.ProviderDeferredException;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.notification.dto.ClaimSnapshot;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * EMAIL side of the channel seam (). It holds exactly what used to be inline in {@code DeliveryOrchestrator}:
 * building the provider request from the claim. Formats friendly sender ("Tenant via Notify" <email>) and
 * extracts tenant reply-to address from metadata.
 */
@Component
public class EmailChannelAdapter implements DeliveryChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmailProvider provider;
    private final TenantRepository tenantRepository;

    @Autowired
    public EmailChannelAdapter(EmailProvider provider, @Nullable TenantRepository tenantRepository) {
        this.provider = provider;
        this.tenantRepository = tenantRepository;
    }

    public EmailChannelAdapter(EmailProvider provider) {
        this(provider, null);
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public String providerName() {
        return provider.name();
    }

    /**
     * A local resilience refusal is recorded as {@code notify-system}, not as the email provider: no request was
     * sent, so naming the provider would be untrue (/ , and the same rule the webhook destination refusals
     * follow). It matters once a notification reaches the deferral cap, because that path does write an attempt row.
     */
    @Override
    public String providerFor(DeliveryResult result) {
        String code = switch (result) {
            case DeliveryResult.Deferred d -> d.code();
            case DeliveryResult.RetryableFailure r -> r.code();
            case DeliveryResult.PermanentFailure p -> p.code();
            case DeliveryResult.Success s -> null;
        };
        return ProviderCallGuard.isLocalRefusal(code) ? DeliveryOrchestrator.SYSTEM_PROVIDER : providerName();
    }

    /**
     * Builds the provider request and sends it.
     */
    @Override
    public DeliveryResult send(ClaimSnapshot claim) {
        String from = formatSender(claim.sender(), resolveTenantName(claim));
        String replyTo = extractReplyTo(claim.metadata());
        try {
            return provider.send(new EmailSendRequest(
                    claim.id() + "-" + claim.deliveryCycle(),
                    from, claim.recipient(), claim.subject(), claim.body(), replyTo));
        } catch (ProviderDeferredException e) {
            log.info("Email attempt deferred locally before any request (notificationId={}, reason={})",
                    claim.id(), e.code());
            return new DeliveryResult.Deferred(e.code(), e.retryAfter());
        }
    }

    private String resolveTenantName(ClaimSnapshot claim) {
        if (tenantRepository == null || claim.tenantId() == null) {
            return null;
        }
        try {
            return tenantRepository.findById(claim.tenantId())
                    .map(TenantEntity::getName)
                    .filter(name -> !name.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Tenant lookup for sender format skipped: {}", e.getMessage());
            return null;
        }
    }

    public static String formatSender(String configuredSender, String tenantName) {
        if (configuredSender == null || configuredSender.isBlank()) {
            return configuredSender;
        }
        if (tenantName == null || tenantName.isBlank()) {
            return configuredSender;
        }
        if (configuredSender.contains("<") && configuredSender.contains(">")) {
            int start = configuredSender.indexOf('<');
            int end = configuredSender.indexOf('>');
            if (start != -1 && end > start) {
                String email = configuredSender.substring(start + 1, end).trim();
                String cleanName = tenantName.replaceAll("[\r\n\"]", "").trim();
                return cleanName.isEmpty() ? configuredSender : String.format("\"%s via Notify\" <%s>", cleanName, email);
            }
            return configuredSender;
        }
        String cleanName = tenantName.replaceAll("[\r\n\"]", "").trim();
        return cleanName.isEmpty() ? configuredSender : String.format("\"%s via Notify\" <%s>", cleanName, configuredSender.trim());
    }

    public static String extractReplyTo(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(metadataJson);
            if (node.hasNonNull("replyTo")) {
                String replyTo = node.get("replyTo").asText().trim();
                return replyTo.isEmpty() ? null : replyTo;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
