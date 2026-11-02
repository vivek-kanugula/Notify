package com.notify.webhook.security;

import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.security.DestinationRejectedException.Reason;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Syntax and host-form half of the destination policy (), applied identically when a tenant configures a URL
 * and before every delivery attempt. It performs no DNS lookup; {@link DestinationResolver} owns resolution,
 * address classification and the pinned connection.
 *
 * <p>Default rule: {@code https}, port 443, a real DNS name. The demo exception (plain {@code http}, an allow-listed
 * host) applies only to an endpoint flagged {@code is_demo}, only for a host in {@code notify.webhook.demo-allowlist},
 * and only outside the {@code prod} profile.
 */
@Component
public class WebhookUrlPolicy {

    public static final int MAX_URL_LENGTH = 2048;

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "metadata.google.internal", "metadata", "instance-data");
    private static final List<String> BLOCKED_SUFFIXES = List.of(
            ".localhost", ".local", ".internal", ".home.arpa", ".onion", ".test", ".invalid", ".example");

    private final WebhookProperties properties;
    private final boolean productionProfile;

    public WebhookUrlPolicy(WebhookProperties properties, Environment environment) {
        this.properties = properties;
        this.productionProfile = List.of(environment.getActiveProfiles()).contains("prod");
    }

    /**
     * A URL accepted by the policy, with the pieces the resolver and sender need. {@code demoException} is true only
     * when the narrow demo/local allowance applied, which is also the only case where a loopback address may be used.
     */
    public record ValidatedUrl(URI uri, String host, int port, boolean https, boolean demoException) {
    }

    /**
     * @param demoEndpoint whether the owning endpoint row is flagged {@code is_demo}
     * @throws DestinationRejectedException when any rule rejects the URL
     */
    public ValidatedUrl validate(String url, boolean demoEndpoint) {
        if (url == null || url.isBlank()) {
            throw new DestinationRejectedException(Reason.INVALID_SYNTAX, "url is blank");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new DestinationRejectedException(Reason.URL_TOO_LONG,
                    "url is " + trimmed.length() + " characters, limit " + MAX_URL_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new DestinationRejectedException(Reason.INVALID_SYNTAX, "url is not a valid URI");
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            throw new DestinationRejectedException(Reason.INVALID_SYNTAX, "url must be absolute and hierarchical");
        }
        if (uri.getRawUserInfo() != null) {
            throw new DestinationRejectedException(Reason.USERINFO_PRESENT, "url must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            throw new DestinationRejectedException(Reason.FRAGMENT_PRESENT, "url must not contain a fragment");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean http = "http".equals(scheme);
        if (!https && !http) {
            throw new DestinationRejectedException(Reason.SCHEME_NOT_ALLOWED, "scheme must be https");
        }

        if (uri.getHost() == null) {
            // java.net.URI only parses an ASCII server-based authority, so an internationalized host arrives here as
            // a null host. Convert the authority to its punycode form and re-parse, which keeps every later rule
            // (userinfo, port, host form, length) working on one canonical URI. An authority that is still not a
            // legal host after conversion is rejected.
            uri = withAsciiAuthority(uri);
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new DestinationRejectedException(Reason.HOST_MISSING, "url has no parsable host");
        }
        String host = normalizeHost(rawHost);
        boolean demoAllowed = demoEndpoint && !productionProfile && properties.demoHostAllowed(host);

        if (host.length() > 253) {
            throw new DestinationRejectedException(Reason.HOST_BLOCKED, "host exceeds 253 characters");
        }

        if (demoAllowed) {
            // Narrow exception: an allow-listed host on an is_demo endpoint outside prod. Plain http, an IP literal,
            // a non-443 port and a loopback address are permitted here and nowhere else.
            int port = uri.getPort();
            int effectivePort = port == -1 ? (https ? 443 : 80) : port;
            if (effectivePort < 1 || effectivePort > 65535) {
                throw new DestinationRejectedException(Reason.PORT_NOT_ALLOWED, "port out of range");
            }
            return new ValidatedUrl(canonical(uri, host), host, effectivePort, https, true);
        }

        if (http) {
            throw new DestinationRejectedException(
                    demoEndpoint ? Reason.HOST_NOT_ALLOWED_FOR_DEMO : Reason.SCHEME_NOT_ALLOWED,
                    demoEndpoint
                            ? "demo destination " + host + " is not in notify.webhook.demo-allowlist for this profile"
                            : "scheme must be https (plain http is limited to an allow-listed demo destination)");
        }
        if (isIpLiteral(rawHost)) {
            throw new DestinationRejectedException(Reason.HOST_IS_IP_LITERAL,
                    "host must be a DNS name, not an IP literal");
        }
        if (BLOCKED_HOSTS.contains(host)) {
            throw new DestinationRejectedException(Reason.HOST_BLOCKED, "host " + host + " is not routable");
        }
        for (String suffix : BLOCKED_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw new DestinationRejectedException(Reason.HOST_BLOCKED,
                        "host suffix " + suffix + " is not a public destination");
            }
        }
        if (!host.contains(".")) {
            throw new DestinationRejectedException(Reason.HOST_BLOCKED,
                    "host must be a fully qualified domain name");
        }

        int port = uri.getPort();
        int effectivePort = port == -1 ? 443 : port;
        if (effectivePort != 443) {
            throw new DestinationRejectedException(Reason.PORT_NOT_ALLOWED,
                    "port " + effectivePort + " is not allowed (443 only)");
        }
        return new ValidatedUrl(canonical(uri, host), host, effectivePort, https, false);
    }

    /**
     * Rebuilds {@code uri} so its authority carries exactly the host that was validated (F4).
     *
     * <p>Without this the two could differ — {@code getHost()} keeps the tenant's spelling (a trailing dot, upper
     * case, the original unicode form) while the policy, the address checks and the connection pin all use the
     * normalized one. The sender dials {@code uri()}, so the host it resolves must be the pinned host or the request
     * fails closed on every attempt.
     *
     * <p>Raw path and query are copied verbatim, so an already percent-encoded path is not encoded a second time and
     * a literal {@code +}, {@code %2F} or {@code &} keeps its meaning.
     */
    private static URI canonical(URI uri, String canonicalHost) {
        String rawHost = uri.getHost();
        if (rawHost != null && rawHost.equals(canonicalHost)) {
            return uri;
        }
        // normalizeHost strips the brackets of an IPv6 literal (only the demo exception can reach one), but a URI
        // authority needs them back.
        String authorityHost = canonicalHost.indexOf(':') >= 0 ? "[" + canonicalHost + "]" : canonicalHost;
        StringBuilder rebuilt = new StringBuilder()
                .append(uri.getScheme()).append("://").append(authorityHost);
        if (uri.getPort() != -1) {
            rebuilt.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            rebuilt.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            rebuilt.append('?').append(uri.getRawQuery());
        }
        try {
            URI normalized = new URI(rebuilt.toString());
            if (normalized.getHost() == null) {
                throw new DestinationRejectedException(Reason.HOST_MISSING, "url has no parsable host");
            }
            return normalized;
        } catch (URISyntaxException e) {
            throw new DestinationRejectedException(Reason.INVALID_SYNTAX, "url is not a valid URI after normalization");
        }
    }

    /**
     * Rebuilds {@code uri} with its authority converted to punycode so {@link URI#getHost()} parses. STD3 ASCII rules
     * apply, so an authority containing a character that is illegal in a host name (an underscore, a space, a stray
     * bracket) is rejected rather than silently accepted. Userinfo in a non-parsing authority is rejected here too,
     * because {@link URI#getRawUserInfo()} cannot see it.
     */
    private static URI withAsciiAuthority(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            throw new DestinationRejectedException(Reason.HOST_MISSING, "url has no authority");
        }
        if (authority.indexOf('@') >= 0) {
            throw new DestinationRejectedException(Reason.USERINFO_PRESENT, "url must not contain credentials");
        }
        String hostPart = authority;
        String portPart = "";
        if (authority.startsWith("[")) {
            // An IPv6 literal: keep it intact so the IP-literal rule rejects it with its own reason.
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new DestinationRejectedException(Reason.HOST_MISSING, "url has an unterminated address literal");
            }
            hostPart = authority.substring(0, close + 1);
            portPart = authority.substring(close + 1);
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                hostPart = authority.substring(0, colon);
                portPart = authority.substring(colon);
            }
        }
        if (hostPart.isBlank()) {
            throw new DestinationRejectedException(Reason.HOST_MISSING, "url has no host in its authority");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(hostPart, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            throw new DestinationRejectedException(Reason.HOST_MISSING,
                    "url host is not a legal name after international conversion");
        }
        StringBuilder rebuilt = new StringBuilder()
                .append(uri.getScheme()).append("://").append(ascii).append(portPart);
        if (uri.getRawPath() != null) {
            rebuilt.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            rebuilt.append('?').append(uri.getRawQuery());
        }
        try {
            URI normalized = new URI(rebuilt.toString());
            if (normalized.getHost() == null) {
                throw new DestinationRejectedException(Reason.HOST_MISSING, "url has no parsable host");
            }
            return normalized;
        } catch (URISyntaxException e) {
            throw new DestinationRejectedException(Reason.INVALID_SYNTAX,
                    "url is not a valid URI after international conversion");
        }
    }

    /** Lower-cased, punycode (ASCII) form of the host, with a single trailing dot removed. */
    static String normalizeHost(String rawHost) {
        String host = rawHost.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new DestinationRejectedException(Reason.HOST_BLOCKED, "host is not a valid international name");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** True for an IPv4 or IPv6 literal. Checked textually so no name resolution happens here. */
    static boolean isIpLiteral(String rawHost) {
        String host = rawHost.trim();
        if (host.startsWith("[") || host.contains(":")) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
