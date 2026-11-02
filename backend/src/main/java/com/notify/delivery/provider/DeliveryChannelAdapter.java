package com.notify.delivery.provider;

import com.notify.common.model.Channel;
import com.notify.delivery.model.DeliveryResult;
import com.notify.notification.dto.ClaimSnapshot;

/**
 * The per-channel send seam (the {@code ChannelDispatcher} of TRD section 12). {@code DeliveryOrchestrator} owns every
 * channel-independent policy — the tenant gate, retry and exhaustion budgets, the deferral cap — and delegates only
 * the outbound call to an adapter. Adding a channel means adding an adapter, never a second orchestration path.
 *
 * <p>Implementations are called <b>outside any transaction</b> and must not open one; that rule is absolute, and the
 * orchestrator enforces it by refusing to call an adapter inside a transaction.
 *
 * <p><b>Preparation reads (/ clarification).</b> The earlier wording "implementations must not touch the
 * database" did not describe the actual seam: the webhook adapter must read the tenant's <em>current</em> endpoint row
 * per attempt (), so a corrected URL heals an in-flight retry. An adapter may therefore perform bounded,
 * self-contained, already-committed <em>preparation</em> reads before it contacts the provider, under two conditions:
 * <ul>
 *   <li>the read happens strictly <em>before</em> any provider request, so no remote effect can exist yet; and</li>
 *   <li>an infrastructure failure of that read is raised as {@link ChannelPreparationException} rather than returned
 *       as a {@link DeliveryResult}, so the worker's classifier and the recovery policy own it. The adapter
 *       itself must not classify it, retry it beyond its own short in-memory budget, or decide a category.</li>
 * </ul>
 * No adapter may write to the database, and no adapter may raise
 * {@link ChannelPreparationException} once a provider request may have been sent.
 *
 * <p>Everything else is a {@link DeliveryResult}: an adapter should map every provider-side outcome to one, and an
 * escaping exception of any other kind is treated as an unknown remote effect and retried by the orchestrator.
 */
public interface DeliveryChannelAdapter {

    Channel channel();

    /** Stable provider name stored on the notification and attempt rows (at most 64 characters). */
    String providerName();

    DeliveryResult send(ClaimSnapshot claim);

    /**
     * Provider name to record for one specific result. The default is {@link #providerName()}, which is right whenever
     * the adapter actually reached the provider.
     *
     * <p>An adapter overrides this for a refusal it decided <em>before</em> any provider contact — no request was made,
     * so attributing the attempt to the provider would be untrue. The webhook adapter uses it for a missing, disabled
     * or policy-blocked endpoint, which CONTRACTS records as {@code delivery_attempts.provider = 'notify-system'},
     * exactly like the tenant gate.
     */
    default String providerFor(DeliveryResult result) {
        return providerName();
    }
}
