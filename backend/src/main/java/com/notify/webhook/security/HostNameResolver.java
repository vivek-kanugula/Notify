package com.notify.webhook.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name resolution seam for the destination policy. Production uses {@link BoundedDnsResolver}, which bounds both the
 * caller's wait and the resources a stalled lookup can hold; tests substitute a stub to exercise mixed A/AAAA
 * answers, DNS rebinding and stalls without any real DNS.
 *
 * <p>The timeout is part of the seam because the attempt's wall-clock budget owns it: the caller passes what is left
 * of that budget, not a fixed configured value ().
 */
public interface HostNameResolver {

    /**
     * @param timeoutMs the longest the caller may wait; implementations must return or throw within it
     * @throws UnknownHostException when the host does not resolve, resolution exceeds {@code timeoutMs}, or the
     *                              resolver has no capacity left
     */
    InetAddress[] resolve(String host, long timeoutMs) throws UnknownHostException;
}
