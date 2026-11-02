package com.notify.delivery.provider.email;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.DeliveryChannelAdapter;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;
import com.notify.delivery.resilience.ProviderCallGuard;
import com.notify.delivery.resilience.ProviderDeferredException;
import com.notify.delivery.service.DeliveryOrchestrator;
import com.notify.notification.dto.ClaimSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EMAIL side of the channel seam (). It holds exactly what used to be inline in {@code DeliveryOrchestrator}:
 * building the provider request from the claim. Behavior is unchanged — the idempotency key is still
 * {@code <notificationId>-<deliveryCycle>} and the sender is still the notification's persisted snapshot ().
 */
@Component
public class EmailChannelAdapter implements DeliveryChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);

    private final EmailProvider provider;

    public EmailChannelAdapter(EmailProvider provider) {
        this.provider = provider;
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
     *
     * <p>A local resilience refusal is translated here, exactly as the webhook adapter translates its own (/
     * , ). The refusal is taken before any request is made, so it becomes the attempt-free
     * {@code DeliveryResult.Deferred}: the claim's attempt is refunded, a deferral is counted and no attempt-history
     * row is written. Without this translation the refusal would escape as a plain {@code RuntimeException} and be
     * classified as a provider failure, which would consume an attempt for a call that never happened.
     */
    @Override
    public DeliveryResult send(ClaimSnapshot claim) {
        try {
            return provider.send(new EmailSendRequest(
                    claim.id() + "-" + claim.deliveryCycle(),
                    claim.sender(), claim.recipient(), claim.subject(), claim.body()));
        } catch (ProviderDeferredException e) {
            log.info("Email attempt deferred locally before any request (notificationId={}, reason={})",
                    claim.id(), e.code());
            return new DeliveryResult.Deferred(e.code(), e.retryAfter());
        }
    }
}
