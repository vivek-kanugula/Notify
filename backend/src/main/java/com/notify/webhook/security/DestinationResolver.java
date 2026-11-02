package com.notify.webhook.security;

import com.notify.webhook.security.DestinationRejectedException.Reason;
import com.notify.webhook.security.WebhookUrlPolicy.ValidatedUrl;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolution half of the destination policy (): validate the URL, resolve the host, and accept only when
 * <em>every</em> returned address is acceptable. The accepted addresses are handed back so the request can be pinned
 * to one of them, which is what closes the DNS-rebinding window — a second resolution between here and the connection
 * cannot move the request to a blocked address.
 *
 * <p>Rejecting when any single answer is blocked (rather than filtering to the good ones) is deliberate: a host that
 * answers with one public and one private address is either misconfigured or hostile, and neither deserves a delivery.
 */
@Component
public class DestinationResolver {

    private final WebhookUrlPolicy urlPolicy;
    private final IpRangePolicy ipPolicy;
    private final HostNameResolver nameResolver;

    public DestinationResolver(WebhookUrlPolicy urlPolicy, IpRangePolicy ipPolicy, HostNameResolver nameResolver) {
        this.urlPolicy = urlPolicy;
        this.ipPolicy = ipPolicy;
        this.nameResolver = nameResolver;
    }

    /** A destination cleared for one request, with the addresses the connection may use. */
    public record PinnedDestination(ValidatedUrl url, List<InetAddress> addresses) {
        public PinnedDestination {
            addresses = List.copyOf(addresses);
        }
    }

    /**
     * @param demoEndpoint whether the owning endpoint row is flagged {@code is_demo}
     * @param dnsTimeoutMs the longest this call may spend resolving; it comes from what is left of the attempt's
     *                     wall-clock budget, so resolution cannot outlive the attempt ()
     * @throws DestinationRejectedException when the URL or any resolved address fails the policy, or resolution does
     *                                      not finish inside {@code dnsTimeoutMs}
     */
    public PinnedDestination resolve(String url, boolean demoEndpoint, long dnsTimeoutMs) {
        ValidatedUrl validated = urlPolicy.validate(url, demoEndpoint);

        InetAddress[] answers;
        try {
            answers = nameResolver.resolve(validated.host(), dnsTimeoutMs);
        } catch (UnknownHostException e) {
            // A timeout and a saturated resolver arrive here too; all three are DNS_FAILURE, which the delivery
            // path treats as retryable (). The message names the host, never a resolved address.
            throw new DestinationRejectedException(Reason.DNS_FAILURE,
                    "host " + validated.host() + " could not be resolved");
        }
        if (answers == null || answers.length == 0) {
            throw new DestinationRejectedException(Reason.DNS_FAILURE,
                    "host " + validated.host() + " resolved to no address");
        }

        List<InetAddress> accepted = new ArrayList<>(answers.length);
        for (InetAddress address : answers) {
            String blocked = ipPolicy.blockReason(address);
            if (blocked != null) {
                boolean loopbackForDemo = validated.demoException() && address != null && address.isLoopbackAddress();
                if (!loopbackForDemo) {
                    // The address itself is not echoed: the reason names the range, which is what an operator needs.
                    throw new DestinationRejectedException(Reason.ADDRESS_BLOCKED,
                            "host " + validated.host() + " resolves into " + blocked);
                }
            }
            accepted.add(address);
        }
        return new PinnedDestination(validated, accepted);
    }
}
