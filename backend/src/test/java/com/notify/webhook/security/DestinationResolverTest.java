package com.notify.webhook.security;

import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.security.DestinationRejectedException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * resolution rules with a stubbed resolver, so mixed answers and DNS rebinding are deterministic and offline.
 *
 * <p>The pinning guarantee itself is proven here by the returned address list: the sender connects only to these
 * addresses, so a later resolution cannot redirect the request. {@code WebhookSenderTest} proves the sender honors it.
 */
class DestinationResolverTest {

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

private static final long DNS_TIMEOUT_MS = 2_000L;

    private static DestinationResolver resolver(HostNameResolver names, String... allowlist) {
        MockEnvironment environment = new MockEnvironment();
        WebhookProperties properties = new WebhookProperties("", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2),
                8192, 4096, "Notify-Webhook/1", List.of(allowlist));
        return new DestinationResolver(new WebhookUrlPolicy(properties, environment), new IpRangePolicy(), names);
    }

    private static HostNameResolver answering(String... literals) {
        return (host, timeoutMs) -> {
            InetAddress[] out = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                out[i] = address(literals[i]);
            }
            return out;
        };
    }

    private static Reason reasonOf(Runnable call) {
        try {
            call.run();
        } catch (DestinationRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected the resolver to reject the destination");
    }

    @Test
    @DisplayName("a host resolving only to public addresses is accepted and every address is returned for pinning")
    void acceptsPublicAddresses() {
        DestinationResolver resolver = resolver(answering("93.184.216.34", "2606:2800:220:1::1"));

        DestinationResolver.PinnedDestination pinned = resolver.resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS);

        assertThat(pinned.url().host()).isEqualTo("hooks.example.com");
        assertThat(pinned.addresses()).containsExactly(address("93.184.216.34"), address("2606:2800:220:1::1"));
    }

    @Test
    @DisplayName("a host resolving to any blocked address is rejected, even when a public address is also returned")
    void rejectsMixedAnswer() {
        assertThat(reasonOf(() -> resolver(answering("93.184.216.34", "10.0.0.5"))
                .resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS))).isEqualTo(Reason.ADDRESS_BLOCKED);
        assertThat(reasonOf(() -> resolver(answering("127.0.0.1"))
                .resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS))).isEqualTo(Reason.ADDRESS_BLOCKED);
        assertThat(reasonOf(() -> resolver(answering("169.254.169.254"))
                .resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS))).isEqualTo(Reason.ADDRESS_BLOCKED);
        assertThat(reasonOf(() -> resolver(answering("::ffff:10.0.0.1"))
                .resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS))).isEqualTo(Reason.ADDRESS_BLOCKED);
    }

    @Test
    @DisplayName("the rejection names the blocked range and the host, never the address the host resolved to")
    void rejectionNamesTheRange() {
        assertThatThrownBy(() -> resolver(answering("10.11.12.13")).resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS))
                .isInstanceOf(DestinationRejectedException.class)
                .hasMessageContaining("private 10.0.0.0/8")
                .hasMessageContaining("hooks.example.com")
                .as("the resolved address is internal information and is not echoed")
                .hasMessageNotContaining("10.11.12.13");

        // A range whose description happens to name a landmark address still names it: that text documents the rule,
        // it is not the resolved answer, and it is what an operator needs to recognize the block.
        assertThatThrownBy(() -> resolver(answering("169.254.169.254")).resolve("https://probe.example.com/h", false, DNS_TIMEOUT_MS))
                .isInstanceOf(DestinationRejectedException.class)
                .hasMessageContaining("link-local 169.254.0.0/16")
                .hasMessageContaining("cloud metadata");
    }

    @Test
    @DisplayName("a DNS failure and an empty answer are both rejected as DNS_FAILURE")
    void dnsFailure() {
        HostNameResolver failing = (host, timeoutMs) -> {
            throw new UnknownHostException(host);
        };
        assertThat(reasonOf(() -> resolver(failing).resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS)))
                .isEqualTo(Reason.DNS_FAILURE);
        assertThat(reasonOf(() -> resolver(answering()).resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS)))
                .isEqualTo(Reason.DNS_FAILURE);
        assertThat(reasonOf(() -> resolver((host, timeoutMs) -> null).resolve("https://hooks.example.com/h", false, DNS_TIMEOUT_MS)))
                .isEqualTo(Reason.DNS_FAILURE);
    }

    @Test
    @DisplayName("DNS rebinding cannot move a request: the validated answer is what the caller pins to")
    void rebindingIsPinnedOut() {
        AtomicInteger calls = new AtomicInteger();
        HostNameResolver rebinding = (host, timeoutMs) -> {
            // First answer public, every later answer loopback: the classic rebinding attack.
            int call = calls.incrementAndGet();
            return new InetAddress[]{address(call == 1 ? "93.184.216.34" : "127.0.0.1")};
        };
        DestinationResolver resolver = resolver(rebinding);

        DestinationResolver.PinnedDestination pinned = resolver.resolve("https://rebind.example.com/h", false, DNS_TIMEOUT_MS);
        assertThat(pinned.addresses()).containsExactly(address("93.184.216.34"));

        // A second resolution now answers with a blocked address and is rejected outright; the first request is
        // unaffected because it carries its own pinned address.
        assertThat(reasonOf(() -> resolver.resolve("https://rebind.example.com/h", false, DNS_TIMEOUT_MS)))
                .isEqualTo(Reason.ADDRESS_BLOCKED);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the demo exception allows a loopback answer, and only loopback")
    void demoExceptionAllowsLoopbackOnly() {
        DestinationResolver demo = resolver(answering("127.0.0.1"), "localhost");
        DestinationResolver.PinnedDestination pinned = demo.resolve("http://localhost:8080/demo/webhook-receiver", true, DNS_TIMEOUT_MS);
        assertThat(pinned.addresses()).containsExactly(address("127.0.0.1"));
        assertThat(pinned.url().demoException()).isTrue();

        // Still blocked for a demo endpoint: link-local metadata, private ranges.
        DestinationResolver metadata = resolver(answering("169.254.169.254"), "localhost");
        assertThat(reasonOf(() -> metadata.resolve("http://localhost/x", true, DNS_TIMEOUT_MS))).isEqualTo(Reason.ADDRESS_BLOCKED);
        DestinationResolver privateRange = resolver(answering("10.1.2.3"), "localhost");
        assertThat(reasonOf(() -> privateRange.resolve("http://localhost/x", true, DNS_TIMEOUT_MS))).isEqualTo(Reason.ADDRESS_BLOCKED);
    }

    @Test
    @DisplayName("an IP-literal demo destination is accepted without any DNS lookup of a name")
    void demoIpLiteral() {
        AtomicInteger calls = new AtomicInteger();
        HostNameResolver counting = (host, timeoutMs) -> {
            calls.incrementAndGet();
            return new InetAddress[]{address("127.0.0.1")};
        };
        DestinationResolver demo = resolver(counting, "127.0.0.1");

        DestinationResolver.PinnedDestination pinned = demo.resolve("http://127.0.0.1:8080/x", true, DNS_TIMEOUT_MS);

        assertThat(pinned.addresses()).containsExactly(address("127.0.0.1"));
        assertThat(calls.get()).as("resolution still goes through the seam, which returns the literal").isEqualTo(1);
    }

    @Test
    @DisplayName("the URL policy runs first, so a bad URL is never resolved")
    void urlPolicyRunsFirst() {
        AtomicInteger calls = new AtomicInteger();
        HostNameResolver counting = (host, timeoutMs) -> {
            calls.incrementAndGet();
            return new InetAddress[]{address("93.184.216.34")};
        };
        DestinationResolver resolver = resolver(counting);

        assertThat(reasonOf(() -> resolver.resolve("http://hooks.example.com/h", false, DNS_TIMEOUT_MS)))
                .isEqualTo(Reason.SCHEME_NOT_ALLOWED);
        assertThat(calls.get()).as("no DNS lookup for a rejected URL").isZero();
    }
}
