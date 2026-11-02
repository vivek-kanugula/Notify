package com.notify.delivery.provider;

import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.fake.FakeEmailProperties;
import com.notify.delivery.provider.fake.FakeEmailProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R9: fake provider hooks and rates are deterministic. */
class FakeEmailProviderTest {

    private static EmailSendRequest to(String address) {
        return new EmailSendRequest("nid-1", "from@example.com", address, "subject", "body");
    }

    private static FakeEmailProvider provider(FakeEmailProperties props) {
        return new FakeEmailProvider(props);
    }

    @Test
    @DisplayName("R9 default is success with a unique fake_ id")
    void defaultSucceeds() {
        FakeEmailProvider fake = provider(FakeEmailProperties.defaults());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            DeliveryResult r = fake.send(to("someone@example.com"));
            assertThat(r).isInstanceOf(DeliveryResult.Success.class);
            String id = ((DeliveryResult.Success) r).messageId();
            assertThat(id).startsWith("fake_");
            ids.add(id);
        }
        assertThat(ids).hasSize(20);
        assertThat(fake.name()).isEqualTo("fake");
    }

    @Test
    @DisplayName("R9 reserved @notify.local hooks: fail-perm, fail-retry and defer are deterministic and case-insensitive")
    void reservedDomainHooks() {
        FakeEmailProvider fake = provider(FakeEmailProperties.defaults());

        assertThat(fake.send(to("fail-perm@notify.local"))).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                r -> assertThat(r.code()).isEqualTo("FAKE_PERMANENT_FAILURE"));
        assertThat(fake.send(to("FAIL-PERM+case9@NOTIFY.LOCAL"))).isInstanceOf(DeliveryResult.PermanentFailure.class);
        assertThat(fake.send(to("fail-retry@notify.local"))).isInstanceOfSatisfying(DeliveryResult.RetryableFailure.class,
                r -> assertThat(r.code()).isEqualTo("FAKE_TEMPORARY_FAILURE"));
        assertThat(fake.send(to("defer@notify.local"))).isInstanceOfSatisfying(DeliveryResult.Deferred.class, r -> {
            assertThat(r.code()).isEqualTo("FAKE_DEFERRED");
            assertThat(r.retryAfter()).isEqualTo(Duration.ofSeconds(5));
        });
        assertThat(fake.send(to("ok@notify.local"))).isInstanceOf(DeliveryResult.Success.class);
    }

    @Test
    @DisplayName("R9 hook names on any other domain never simulate a failure")
    void hooksOnlyOnReservedDomain() {
        FakeEmailProvider fake = provider(FakeEmailProperties.defaults());
        assertThat(fake.send(to("fail-perm@example.com"))).isInstanceOf(DeliveryResult.Success.class);
        assertThat(fake.send(to("fail-retry@notify.local.evil.com"))).isInstanceOf(DeliveryResult.Success.class);
        assertThat(fake.send(to("defer@sub.notify.local"))).isInstanceOf(DeliveryResult.Success.class);
    }

    @Test
    @DisplayName("R9 configured defer duration is honored")
    void deferDurationConfigurable() {
        FakeEmailProvider fake = provider(new FakeEmailProperties(0, 0, 0, null, 17));
        assertThat(fake.send(to("defer@notify.local"))).isInstanceOfSatisfying(DeliveryResult.Deferred.class,
                r -> assertThat(r.retryAfter()).isEqualTo(Duration.ofSeconds(17)));
    }

    @Test
    @DisplayName("R9 failure rates of 1.0 fail every send; permanent wins over temporary")
    void rates() {
        assertThat(provider(new FakeEmailProperties(0, 1.0, 0, null, 5)).send(to("a@example.com")))
                .isInstanceOf(DeliveryResult.RetryableFailure.class);
        assertThat(provider(new FakeEmailProperties(0, 0, 1.0, null, 5)).send(to("a@example.com")))
                .isInstanceOf(DeliveryResult.PermanentFailure.class);
        assertThat(provider(new FakeEmailProperties(0, 1.0, 1.0, null, 5)).send(to("a@example.com")))
                .isInstanceOf(DeliveryResult.PermanentFailure.class);
    }

    @Test
    @DisplayName("R9 a seed makes the random outcome sequence reproducible")
    void seededSequenceIsReproducible() {
        List<String> first = sequence(new FakeEmailProperties(0, 0.3, 0.2, 1234L, 5));
        List<String> second = sequence(new FakeEmailProperties(0, 0.3, 0.2, 1234L, 5));
        assertThat(first).isEqualTo(second);
        assertThat(first).contains("TEMP", "PERM", "OK");
    }

    private static List<String> sequence(FakeEmailProperties props) {
        FakeEmailProvider fake = provider(props);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            DeliveryResult r = fake.send(to("r" + i + "@example.com"));
            out.add(r instanceof DeliveryResult.Success ? "OK"
                    : r instanceof DeliveryResult.RetryableFailure ? "TEMP" : "PERM");
        }
        return out;
    }

    @Test
    @DisplayName("R9 configured latency is simulated")
    void latency() {
        FakeEmailProvider fake = provider(new FakeEmailProperties(60, 0, 0, null, 5));
        long begin = System.nanoTime();
        fake.send(to("a@example.com"));
        assertThat(Duration.ofNanos(System.nanoTime() - begin).toMillis()).isGreaterThanOrEqualTo(55);
    }

    @Test
    @DisplayName("R9 invalid properties are rejected")
    void validatesProperties() {
        assertThatThrownBy(() -> new FakeEmailProperties(-1, 0, 0, null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeEmailProperties(0, 1.1, 0, null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeEmailProperties(0, 0, -0.1, null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeEmailProperties(0, 0, 0, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R7 the request object never prints recipient or body")
    void requestToStringIsSafe() {
        EmailSendRequest request = new EmailSendRequest("nid-9", "from@example.com", "private@example.com", "secret subject", "secret body");
        assertThat(request.toString()).contains("nid-9").doesNotContain("private@").doesNotContain("secret");
    }
}
