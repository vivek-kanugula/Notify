package com.notify.delivery.provider.webhook;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The connection-time half of the SSRF guard (, Option A): the HTTP client never performs its own name
 * resolution. The sender validates the destination, then publishes the accepted addresses here for the duration of
 * one request, so the connection can only go to an address the policy already cleared. A second DNS answer between
 * validation and connect — the DNS-rebinding attack — therefore cannot move the request.
 *
 * <p>Fail closed: a host that was not pinned by the current thread is unresolvable, so no other code path can borrow
 * this client to reach an unvalidated host.
 */
@Component
public class PinnedDnsResolver implements DnsResolver {

    private static final ThreadLocal<Map<String, InetAddress[]>> PINNED = new ThreadLocal<>();

    /** Runs {@code action} with {@code host} pinned to {@code addresses}; the pin never outlives the call. */
    public <T> T withPinned(String host, List<InetAddress> addresses, PinnedCall<T> action) throws Exception {
        if (host == null || addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("a pinned host needs at least one validated address");
        }
        Map<String, InetAddress[]> previous = PINNED.get();
        PINNED.set(Map.of(host.toLowerCase(Locale.ROOT), addresses.toArray(InetAddress[]::new)));
        try {
            return action.call();
        } finally {
            if (previous == null) {
                PINNED.remove();
            } else {
                PINNED.set(previous);
            }
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Map<String, InetAddress[]> pinned = PINNED.get();
        InetAddress[] addresses = pinned == null ? null : pinned.get(host == null ? null : host.toLowerCase(Locale.ROOT));
        if (addresses == null) {
            throw new UnknownHostException("host " + host + " was not validated and pinned for this request");
        }
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        // Never do a reverse lookup: the canonical name is only used for logging and authentication schemes we do
        // not use, and a lookup here would be a second, unvalidated resolution.
        resolve(host);
        return host;
    }

    /** A call made while a host is pinned. */
    @FunctionalInterface
    public interface PinnedCall<T> {
        T call() throws Exception;
    }
}
