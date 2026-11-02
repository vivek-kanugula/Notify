package com.notify.webhook.security;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Pure address classification for the webhook destination policy (). No DNS, no I/O, no state.
 *
 * <p>Blocks every address family that can reach Notify's own host, a private network, or cloud metadata:
 * loopback, this-network, private, carrier-grade NAT, link-local (including {@code 169.254.169.254}), unique-local,
 * multicast, broadcast, reserved, benchmark and documentation ranges. IPv6 forms that embed an IPv4 address
 * (IPv4-mapped, IPv4-compatible, NAT64, 6to4) are decided on the embedded address, so {@code ::ffff:127.0.0.1} is
 * blocked for the same reason as {@code 127.0.0.1}. Teredo is blocked outright: it is never a legitimate destination
 * and its embedded addresses are obfuscated.
 */
@Component
public class IpRangePolicy {

    /** Why an address was rejected; {@code null} from {@link #blockReason} means the address is acceptable. */
    public String blockReason(InetAddress address) {
        if (address == null) {
            return "address is null";
        }
        if (address instanceof Inet4Address v4) {
            return ipv4BlockReason(v4.getAddress());
        }
        if (address instanceof Inet6Address v6) {
            return ipv6BlockReason(v6);
        }
        return "unknown address family";
    }

    public boolean isBlocked(InetAddress address) {
        return blockReason(address) != null;
    }

    // ─── IPv4 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private static String ipv4BlockReason(byte[] b) {
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        int a2 = b[2] & 0xFF;

        if (a0 == 0) {
            return "this-network 0.0.0.0/8";
        }
        if (a0 == 10) {
            return "private 10.0.0.0/8";
        }
        if (a0 == 100 && a1 >= 64 && a1 <= 127) {
            return "carrier-grade NAT 100.64.0.0/10";
        }
        if (a0 == 127) {
            return "loopback 127.0.0.0/8";
        }
        if (a0 == 169 && a1 == 254) {
            return "link-local 169.254.0.0/16 (includes cloud metadata 169.254.169.254)";
        }
        if (a0 == 172 && a1 >= 16 && a1 <= 31) {
            return "private 172.16.0.0/12";
        }
        if (a0 == 192 && a1 == 0 && a2 == 0) {
            return "IETF protocol assignments 192.0.0.0/24";
        }
        if (a0 == 192 && a1 == 0 && a2 == 2) {
            return "documentation 192.0.2.0/24";
        }
        if (a0 == 192 && a1 == 88 && a2 == 99) {
            return "6to4 relay anycast 192.88.99.0/24";
        }
        if (a0 == 192 && a1 == 168) {
            return "private 192.168.0.0/16";
        }
        if (a0 == 198 && (a1 == 18 || a1 == 19)) {
            return "benchmark 198.18.0.0/15";
        }
        if (a0 == 198 && a1 == 51 && a2 == 100) {
            return "documentation 198.51.100.0/24";
        }
        if (a0 == 203 && a1 == 0 && a2 == 113) {
            return "documentation 203.0.113.0/24";
        }
        if (a0 >= 224 && a0 <= 239) {
            return "multicast 224.0.0.0/4";
        }
        if (a0 >= 240) {
            return "reserved 240.0.0.0/4 (includes broadcast 255.255.255.255)";
        }
        return null;
    }

    // ─── IPv6 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private static String ipv6BlockReason(Inet6Address v6) {
        byte[] b = v6.getAddress();

        // Embedded-IPv4 forms are decided on the embedded address.
        if (isIpv4Mapped(b)) {
            String embedded = ipv4BlockReason(tail4(b));
            return embedded == null ? null : "IPv4-mapped ::ffff:0:0/96 of " + embedded;
        }
        if (isIpv4Compatible(b)) {
            String embedded = ipv4BlockReason(tail4(b));
            return embedded == null ? "IPv4-compatible ::/96 is deprecated" : "IPv4-compatible ::/96 of " + embedded;
        }
        if (isNat64(b)) {
            String embedded = ipv4BlockReason(tail4(b));
            return embedded == null ? null : "NAT64 64:ff9b::/96 of " + embedded;
        }
        if (is6to4(b)) {
            byte[] embedded4 = {b[2], b[3], b[4], b[5]};
            String embedded = ipv4BlockReason(embedded4);
            return embedded == null ? null : "6to4 2002::/16 of " + embedded;
        }
        if (isTeredo(b)) {
            return "Teredo 2001::/32";
        }

        if (v6.isAnyLocalAddress()) {
            return "unspecified ::/128";
        }
        if (v6.isLoopbackAddress()) {
            return "loopback ::1";
        }
        if (v6.isLinkLocalAddress()) {
            return "link-local fe80::/10";
        }
        if (v6.isSiteLocalAddress()) {
            return "deprecated site-local fec0::/10";
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return "unique-local fc00::/7";
        }
        if (v6.isMulticastAddress()) {
            return "multicast ff00::/8";
        }
        if (isPrefix(b, new int[]{0x01, 0x00}, 8) && allZero(b, 2, 8)) {
            return "discard-only 100::/64";
        }
        if (isPrefix(b, new int[]{0x20, 0x01, 0x0D, 0xB8}, 32)) {
            return "documentation 2001:db8::/32";
        }
        return null;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        return allZero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }

    private static boolean isIpv4Compatible(byte[] b) {
        // ::/96 excluding :: and ::1, which the unspecified/loopback checks own.
        if (!allZero(b, 0, 12)) {
            return false;
        }
        int last = ((b[12] & 0xFF) << 24) | ((b[13] & 0xFF) << 16) | ((b[14] & 0xFF) << 8) | (b[15] & 0xFF);
        return last != 0 && last != 1;
    }

    private static boolean isNat64(byte[] b) {
        return (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && allZero(b, 4, 12);
    }

    private static boolean is6to4(byte[] b) {
        return (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02;
    }

    private static boolean isTeredo(byte[] b) {
        return (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x00 && (b[3] & 0xFF) == 0x00;
    }

    private static boolean isPrefix(byte[] b, int[] prefix, int bits) {
        int fullBytes = bits / 8;
        for (int i = 0; i < fullBytes && i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(byte[] b, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tail4(byte[] b) {
        return new byte[]{b[12], b[13], b[14], b[15]};
    }
}
