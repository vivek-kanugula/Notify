package com.notify.webhook.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * address classification. Every literal here is parsed by {@link InetAddress#getByName(String)}, which does no
 * DNS for a literal, so this test is pure and offline.
 */
class IpRangePolicyTest {

    private final IpRangePolicy policy = new IpRangePolicy();

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not a literal: " + literal, e);
        }
    }

    @ParameterizedTest(name = "blocked: {0}")
    @ValueSource(strings = {
            // IPv4
            "0.0.0.0", "0.1.2.3",
            "10.0.0.1", "10.255.255.255",
            "100.64.0.1", "100.127.255.255",
            "127.0.0.1", "127.1.2.3",
            "169.254.0.1", "169.254.169.254",
            "172.16.0.1", "172.31.255.255",
            "192.0.0.1", "192.0.2.5", "192.88.99.1",
            "192.168.0.1", "192.168.255.255",
            "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9",
            "224.0.0.1", "239.255.255.255",
            "240.0.0.1", "255.255.255.255",
            // IPv6
            "::", "::1", "fe80::1", "fec0::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "100::1", "2001:db8::1",
            "2001:0:1234::1",                 // Teredo
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254",
            "64:ff9b::127.0.0.1", "64:ff9b::192.168.1.1",
            "2002:7f00:0001::1",              // 6to4 embedding 127.0.0.1
            "2002:c0a8:0101::1"               // 6to4 embedding 192.168.1.1
    })
    void blockedAddresses(String literal) {
        InetAddress address = address(literal);
        assertThat(policy.isBlocked(address)).as("%s must be blocked", literal).isTrue();
        assertThat(policy.blockReason(address)).as("%s has a reason", literal).isNotBlank();
    }

    @ParameterizedTest(name = "allowed: {0}")
    @ValueSource(strings = {
            "1.1.1.1", "8.8.8.8", "93.184.216.34", "203.0.114.1", "198.20.0.1", "100.63.255.255", "100.128.0.1",
            "172.15.255.255", "172.32.0.1", "192.167.255.255", "192.169.0.1", "223.255.255.255",
            "2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450:4001::1",
            "::ffff:8.8.8.8", "64:ff9b::8.8.8.8", "2002:0808:0808::1"
    })
    void allowedAddresses(String literal) {
        InetAddress address = address(literal);
        assertThat(policy.blockReason(address)).as("%s must be allowed", literal).isNull();
        assertThat(policy.isBlocked(address)).isFalse();
    }

    @Test
    @DisplayName("a null address is blocked rather than treated as acceptable")
    void nullAddress() {
        assertThat(policy.isBlocked(null)).isTrue();
        assertThat(policy.blockReason(null)).isEqualTo("address is null");
    }

    @Test
    @DisplayName("the reason names the range so an operator can act on it")
    void reasonsNameTheRange() {
        assertThat(policy.blockReason(address("169.254.169.254"))).contains("link-local").contains("metadata");
        assertThat(policy.blockReason(address("10.1.2.3"))).contains("10.0.0.0/8");
        assertThat(policy.blockReason(address("::1"))).contains("loopback");
        assertThat(policy.blockReason(address("2001:0:1234::1"))).contains("Teredo");
        assertThat(policy.blockReason(address("fd00::1"))).contains("unique-local");
        assertThat(policy.blockReason(address("64:ff9b::192.168.1.1"))).contains("NAT64").contains("192.168.0.0/16");
        assertThat(policy.blockReason(address("2002:7f00:0001::1"))).contains("6to4").contains("loopback");
        // InetAddress collapses an IPv4-mapped *literal* to an Inet4Address, so the literal reports the embedded
        // range directly; the mapped IPv6 form itself is covered by mappedIpv6FormIsDecidedOnTheEmbeddedAddress().
        assertThat(policy.blockReason(address("::ffff:127.0.0.1"))).isEqualTo("loopback 127.0.0.0/8");
    }

    @Test
    @DisplayName("a genuine IPv4-mapped IPv6 address (as a resolver can return) is decided on the embedded address")
    void mappedIpv6FormIsDecidedOnTheEmbeddedAddress() throws UnknownHostException {
        assertThat(mapped(127, 0, 0, 1))
                .as("the test must exercise the Inet6Address branch, not a collapsed Inet4Address")
                .isInstanceOf(Inet6Address.class);

        assertThat(policy.blockReason(mapped(127, 0, 0, 1))).contains("IPv4-mapped").contains("loopback");
        assertThat(policy.blockReason(mapped(10, 0, 0, 5))).contains("IPv4-mapped").contains("10.0.0.0/8");
        assertThat(policy.blockReason(mapped(169, 254, 169, 254))).contains("IPv4-mapped").contains("link-local");
        assertThat(policy.blockReason(mapped(8, 8, 8, 8))).as("a public embedded address is allowed").isNull();
    }

    /**
     * Builds an {@link Inet6Address} holding the IPv4-mapped form. {@code InetAddress.getByName} and
     * {@code InetAddress.getByAddress} both collapse that form to an {@code Inet4Address}, so the Inet6-specific
     * factory is the only way to reach the mapped branch of the policy.
     */
    private static InetAddress mapped(int a, int b, int c, int d) throws UnknownHostException {
        byte[] raw = new byte[16];
        raw[10] = (byte) 0xFF;
        raw[11] = (byte) 0xFF;
        raw[12] = (byte) a;
        raw[13] = (byte) b;
        raw[14] = (byte) c;
        raw[15] = (byte) d;
        return Inet6Address.getByAddress(null, raw, 0);
    }
}
