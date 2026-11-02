package com.notify.webhook.security;

import com.notify.webhook.config.WebhookProperties;
import com.notify.webhook.security.DestinationRejectedException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** syntax and host-form rules, plus the narrow demo exception. No DNS happens in this class. */
class WebhookUrlPolicyTest {

    private static WebhookUrlPolicy policy(List<String> allowlist, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        WebhookProperties properties = new WebhookProperties("", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2),
                8192, 4096, "Notify-Webhook/1", allowlist);
        return new WebhookUrlPolicy(properties, environment);
    }

    private final WebhookUrlPolicy policy = policy(List.of());
    private final WebhookUrlPolicy localPolicy = policy(List.of("localhost", "127.0.0.1"));
    private final WebhookUrlPolicy prodPolicy = policy(List.of("localhost"), "prod");

    private static Reason reasonOf(Runnable call) {
        try {
            call.run();
        } catch (DestinationRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("expected the policy to reject the url");
    }

    @Test
    @DisplayName("a plain https URL on port 443 is accepted and normalized")
    void acceptsHttps() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate("https://Hooks.Example.COM/notify?x=1", false);

        assertThat(validated.host()).isEqualTo("hooks.example.com");
        assertThat(validated.port()).isEqualTo(443);
        assertThat(validated.https()).isTrue();
        assertThat(validated.demoException()).isFalse();
        assertThat(validated.uri().getPath()).isEqualTo("/notify");
        assertThat(policy.validate("https://hooks.example.com:443/x", false).port()).isEqualTo(443);
        assertThat(policy.validate("https://hooks.example.com./x", false).host())
                .as("a single trailing dot is normalized away").isEqualTo("hooks.example.com");
    }

    @Test
    @DisplayName("an international domain is accepted in punycode form, and so is one already written in punycode")
    void acceptsIdn() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate("https://bücher.example.com/hook?q=ü", false);
        assertThat(validated.host()).isEqualTo("xn--bcher-kva.example.com");
        assertThat(validated.port()).isEqualTo(443);
        assertThat(validated.uri().getRawPath()).as("the path and query survive the conversion").isEqualTo("/hook");
        // The policy normalizes the host only; the path and query are kept exactly as the tenant wrote them. The
        // sender percent-encodes them with URI.toASCIIString() before the request line is built.
        assertThat(validated.uri().getRawQuery()).isEqualTo("q=ü");
        assertThat(validated.uri().toASCIIString()).isEqualTo("https://xn--bcher-kva.example.com/hook?q=%C3%BC");
        assertThat(policy.validate("https://xn--bcher-kva.example.com/hook", false).host())
                .isEqualTo("xn--bcher-kva.example.com");
        assertThat(policy.validate("https://BÜCHER.example.com:443/hook", false).host())
                .as("case folding happens after conversion").isEqualTo("xn--bcher-kva.example.com");
    }

    @Test
    @DisplayName("the international path never relaxes a rule: credentials, ports and blocked hosts are still rejected")
    void idnKeepsEveryRule() {
        assertThat(reasonOf(() -> policy.validate("https://user:pass@bücher.example.com/h", false)))
                .isEqualTo(Reason.USERINFO_PRESENT);
        assertThat(reasonOf(() -> policy.validate("https://bücher.example.com:8443/h", false)))
                .isEqualTo(Reason.PORT_NOT_ALLOWED);
        assertThat(reasonOf(() -> policy.validate("http://bücher.example.com/h", false)))
                .isEqualTo(Reason.SCHEME_NOT_ALLOWED);
        assertThat(reasonOf(() -> policy.validate("https://münchen.local/h", false)))
                .as("a blocked suffix is still blocked after conversion").isEqualTo(Reason.HOST_BLOCKED);
        assertThat(reasonOf(() -> policy.validate("https://bücher.example/h", false)))
                .as("a reserved TLD is still blocked after conversion").isEqualTo(Reason.HOST_BLOCKED);
        assertThat(reasonOf(() -> policy.validate("https://bü cher.example.com/h", false)))
                .as("a space makes the URL unparsable before any host conversion").isEqualTo(Reason.INVALID_SYNTAX);
        assertThat(reasonOf(() -> policy.validate("https://münchen_hooks.example.com/h", false)))
                .as("an underscore is not a legal host character").isEqualTo(Reason.HOST_MISSING);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "''                                      | INVALID_SYNTAX",
            "'   '                                   | INVALID_SYNTAX",
            "not a url                               | INVALID_SYNTAX",
            "/relative/only                          | INVALID_SYNTAX",
            "mailto:someone@example.com              | INVALID_SYNTAX",
            "ftp://files.example.com/hook            | SCHEME_NOT_ALLOWED",
            "http://hooks.example.com/hook           | SCHEME_NOT_ALLOWED",
            "https://user:pass@hooks.example.com/h   | USERINFO_PRESENT",
            "https://hooks.example.com/h#frag        | FRAGMENT_PRESENT",
            "https://hooks.example.com:8443/h        | PORT_NOT_ALLOWED",
            "https://hooks.example.com:80/h          | PORT_NOT_ALLOWED",
            "https://localhost/h                     | HOST_BLOCKED",
            "https://api.localhost/h                 | HOST_BLOCKED",
            "https://printer.local/h                 | HOST_BLOCKED",
            "https://db.internal/h                   | HOST_BLOCKED",
            "https://metadata.google.internal/h      | HOST_BLOCKED",
            "https://thing.home.arpa/h               | HOST_BLOCKED",
            "https://site.onion/h                    | HOST_BLOCKED",
            "https://host.test/h                     | HOST_BLOCKED",
            "https://singlelabel/h                   | HOST_BLOCKED",
            "https://127.0.0.1/h                     | HOST_IS_IP_LITERAL",
            "https://169.254.169.254/latest          | HOST_IS_IP_LITERAL",
            "https://8.8.8.8/h                       | HOST_IS_IP_LITERAL",
            "https://[::1]/h                         | HOST_IS_IP_LITERAL",
            "https:///nohost                         | HOST_MISSING",
            "https://under_score.example.com/h       | HOST_MISSING"
    })
    void rejects(String url, Reason expected) {
        // The table pads for readability; the policy is given the untrimmed value apart from that padding.
        assertThat(reasonOf(() -> policy.validate(url.isBlank() ? url : url.trim(), false))).isEqualTo(expected);
    }

    @Test
    @DisplayName("a null or blank url is rejected as INVALID_SYNTAX, not with a NullPointerException")
    void rejectsNullAndBlank() {
        assertThat(reasonOf(() -> policy.validate(null, false))).isEqualTo(Reason.INVALID_SYNTAX);
        assertThat(reasonOf(() -> policy.validate("", false))).isEqualTo(Reason.INVALID_SYNTAX);
        assertThat(reasonOf(() -> policy.validate("   ", false))).isEqualTo(Reason.INVALID_SYNTAX);
        assertThat(reasonOf(() -> policy.validate(null, true))).isEqualTo(Reason.INVALID_SYNTAX);
    }

    @Test
    @DisplayName("an over-long URL is rejected before anything else inspects it")
    void rejectsOverLongUrl() {
        String url = "https://hooks.example.com/" + "a".repeat(WebhookUrlPolicy.MAX_URL_LENGTH);
        assertThat(reasonOf(() -> policy.validate(url, false))).isEqualTo(Reason.URL_TOO_LONG);
    }

    @Test
    @DisplayName("a 254-character host is rejected")
    void rejectsOverLongHost() {
        String host = ("a".repeat(60) + ".").repeat(4) + "example.com";
        assertThat(host.length()).isGreaterThan(253);
        assertThat(reasonOf(() -> policy.validate("https://" + host + "/h", false))).isEqualTo(Reason.HOST_BLOCKED);
    }

    @Test
    @DisplayName("the demo exception needs the endpoint flag, the allowlist and a non-prod profile together")
    void demoException() {
        // All three present: plain http to a loopback host on any port is allowed.
        WebhookUrlPolicy.ValidatedUrl validated =
                localPolicy.validate("http://localhost:8080/demo/webhook-receiver", true);
        assertThat(validated.demoException()).isTrue();
        assertThat(validated.port()).isEqualTo(8080);
        assertThat(validated.https()).isFalse();
        assertThat(localPolicy.validate("http://127.0.0.1:9999/x", true).demoException()).isTrue();

        // Endpoint not flagged is_demo.
        assertThat(reasonOf(() -> localPolicy.validate("http://localhost:8080/x", false)))
                .isEqualTo(Reason.SCHEME_NOT_ALLOWED);
        // Flagged, but the host is not allow-listed.
        assertThat(reasonOf(() -> localPolicy.validate("http://other.host/x", true)))
                .isEqualTo(Reason.HOST_NOT_ALLOWED_FOR_DEMO);
        // Flagged and allow-listed, but the profile is prod.
        assertThat(reasonOf(() -> prodPolicy.validate("http://localhost:8080/x", true)))
                .isEqualTo(Reason.HOST_NOT_ALLOWED_FOR_DEMO);
        // An empty allowlist grants nothing.
        assertThat(reasonOf(() -> policy.validate("http://localhost:8080/x", true)))
                .isEqualTo(Reason.HOST_NOT_ALLOWED_FOR_DEMO);
    }

    @Test
    @DisplayName("the demo exception never relaxes the rules for a public destination")
    void demoExceptionDoesNotRelaxPublicRules() {
        assertThat(reasonOf(() -> localPolicy.validate("http://hooks.example.com/h", true)))
                .isEqualTo(Reason.HOST_NOT_ALLOWED_FOR_DEMO);
        assertThat(reasonOf(() -> localPolicy.validate("https://hooks.example.com:8443/h", true)))
                .isEqualTo(Reason.PORT_NOT_ALLOWED);
    }

    @Test
    @DisplayName("isIpLiteral recognizes IPv4 and IPv6 literals without resolving anything")
    void ipLiteralDetection() {
        assertThat(WebhookUrlPolicy.isIpLiteral("127.0.0.1")).isTrue();
        assertThat(WebhookUrlPolicy.isIpLiteral("255.255.255.255")).isTrue();
        assertThat(WebhookUrlPolicy.isIpLiteral("[::1]")).isTrue();
        assertThat(WebhookUrlPolicy.isIpLiteral("::1")).isTrue();
        assertThat(WebhookUrlPolicy.isIpLiteral("hooks.example.com")).isFalse();
        assertThat(WebhookUrlPolicy.isIpLiteral("256.1.1.1")).isFalse();
        assertThat(WebhookUrlPolicy.isIpLiteral("1.2.3")).isFalse();
    }

    @Test
    @DisplayName("a rejection message names the rule but never claims who owns the address")
    void rejectionMessage() {
        assertThatThrownBy(() -> policy.validate("https://169.254.169.254/latest/meta-data", false))
                .isInstanceOf(DestinationRejectedException.class)
                .hasMessageContaining("HOST_IS_IP_LITERAL");
    }
    // ─── F4: the URI handed to the sender carries the host that was validated and pinned ──────────────────────────

    @Test
    @DisplayName("F4 a trailing-dot host is canonicalized, so uri() and host() agree and the pin matches")
    void trailingDotHostIsCanonicalized() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate("https://receiver.example.com./hook", false);

        assertThat(validated.host()).isEqualTo("receiver.example.com");
        assertThat(validated.uri().getHost())
                .as("the sender dials uri(); it must be the host the resolver pinned")
                .isEqualTo("receiver.example.com");
        assertThat(validated.uri().toASCIIString()).isEqualTo("https://receiver.example.com/hook");
    }

    @Test
    @DisplayName("F4 an upper-case host is lower-cased in both the host and the URI")
    void upperCaseHostIsCanonicalized() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate("https://Receiver.EXAMPLE.com/hook", false);

        assertThat(validated.host()).isEqualTo("receiver.example.com");
        assertThat(validated.uri().getHost()).isEqualTo("receiver.example.com");
    }

    @Test
    @DisplayName("F4 an internationalized host reaches the sender as punycode, matching the pinned host")
    void idnHostIsCanonicalized() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate("https://b\u00fccher.example.com/hook", false);

        assertThat(validated.host()).isEqualTo("xn--bcher-kva.example.com");
        assertThat(validated.uri().getHost()).isEqualTo("xn--bcher-kva.example.com");
    }

    @Test
    @DisplayName("F4 the demo destination is canonicalized too, port and scheme preserved")
    void demoDestinationIsCanonicalized() {
        WebhookUrlPolicy.ValidatedUrl validated =
                localPolicy.validate("http://LOCALHOST:9099/demo/webhook-receiver", true);

        assertThat(validated.host()).isEqualTo("localhost");
        assertThat(validated.uri().getHost()).isEqualTo("localhost");
        assertThat(validated.uri().getPort()).isEqualTo(9099);
        assertThat(validated.uri().getScheme()).isEqualTo("http");
        assertThat(validated.port()).isEqualTo(9099);
        assertThat(validated.demoException()).isTrue();
    }

    @Test
    @DisplayName("F4 canonicalization keeps the raw path and query byte for byte: no double encoding, no lost meaning")
    void rawPathAndQueryAreUntouched() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate(
                "https://Receiver.example.com./a%2Fb/c+d/%20e?q=a%2Bb&r=x+y&s=%E2%98%83", false);

        assertThat(validated.uri().getRawPath()).isEqualTo("/a%2Fb/c+d/%20e");
        assertThat(validated.uri().getRawQuery()).isEqualTo("q=a%2Bb&r=x+y&s=%E2%98%83");
        // The percent-encoding is passed through, never encoded a second time.
        assertThat(validated.uri().toASCIIString())
                .isEqualTo("https://receiver.example.com/a%2Fb/c+d/%20e?q=a%2Bb&r=x+y&s=%E2%98%83");
    }

    @Test
    @DisplayName("F4 a host that needs no canonicalization keeps the original URI untouched")
    void alreadyCanonicalUrlIsUnchanged() {
        WebhookUrlPolicy.ValidatedUrl validated = policy.validate("https://receiver.example.com/hook?x=1", false);

        assertThat(validated.uri().toString()).isEqualTo("https://receiver.example.com/hook?x=1");
    }
    @Test
    @DisplayName("F4 an IPv6 demo destination keeps its brackets in the URI while the pinned host has none")
    void ipv6DemoDestinationKeepsItsBrackets() {
        WebhookUrlPolicy ipv6Policy = policy(List.of("::1"));

        WebhookUrlPolicy.ValidatedUrl validated = ipv6Policy.validate("http://[::1]:9099/demo/webhook-receiver", true);

        assertThat(validated.host()).isEqualTo("::1");
        assertThat(validated.uri().getHost()).isEqualTo("[::1]");
        assertThat(validated.uri().toASCIIString()).isEqualTo("http://[::1]:9099/demo/webhook-receiver");
        assertThat(validated.port()).isEqualTo(9099);
    }
}