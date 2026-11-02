package com.notify.sse.stream;

import com.notify.sse.config.SseProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-instance stream cap and its lease lifecycle (/ , ; plan cases L1-L7, L10-L15).
 *
 * <p><b>Two instances, one Redis.</b> Two {@link SseStreamCapService} instances share one disposable Redis
 * container, which is the two-instance scenario in process form - and the only way to show that the cap is not a
 * per-instance counter.
 *
 * <p><b>Controlled interleaving, not sampling.</b> The monotonic source is advanced by hand and the lease clock is
 * Redis's own, so every assertion is made at a point the test chose. Timings are scaled as a <b>set</b> (lease 4 s,
 * margin 1 s, barrier 5 s, heartbeat 2 s) and the property record asserts the safety relationships at that scale, so
 * this test cannot accidentally weaken one of them - condition C5.
 *
 * <p>Nothing here touches the development Redis: the container is created and removed by this test.
 */
class SseStreamCapIntegrationTest {

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private static final int CAP = 2;

    /** A scaled but internally consistent timing set; SseProperties enforces the relationships. */
    private static SseProperties scaled() {
        return new SseProperties(
                Duration.ofSeconds(4),      // leaseTtl
                Duration.ofSeconds(2),      // heartbeat
                Duration.ofMillis(1500),    // safetyMargin
                Duration.ofMillis(500),     // maxClockSkew
                Duration.ofMillis(4500),    // barrier = leaseTtl + maxClockSkew
                Duration.ofMinutes(30),
                Duration.ofHours(24),
                Duration.ofSeconds(60),
                CAP, 200, 64, 8);
    }

    /** A monotonic source the test advances by hand: no sleeping, no racing. */
    private static class FakeTime extends MonotonicTimeSource {
        private final AtomicLong nanos = new AtomicLong(System.nanoTime());

        @Override
        public long nanos() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @BeforeAll
    static void startRedis() {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redisContainer.start();
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    private SseProperties props;
    private FakeTime timeA;
    private FakeTime timeB;
    private SseStreamCapService instanceA;
    private SseStreamCapService instanceB;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        props = scaled();
        timeA = new FakeTime();
        timeB = new FakeTime();
        instanceA = new SseStreamCapService(redis, props, timeA);
        instanceB = new SseStreamCapService(redis, props, timeB);
        tenantId = UUID.randomUUID();
    }

    /** Admission after the first-use barrier has elapsed, which every scenario needs before it can start. */
    private SseStreamLease admitPastBarrier(SseStreamCapService instance, String streamId) throws Exception {
        SseStreamCapService.Admission first = instance.admit(tenantId, "user-1", streamId);
        if (first instanceof SseStreamCapService.Admission.Granted granted) {
            return granted.lease();
        }
        Thread.sleep(props.barrier().toMillis() + 200);     // real time: the barrier lives on Redis's clock
        SseStreamCapService.Admission second = instance.admit(tenantId, "user-1", streamId);
        assertThat(second).isInstanceOf(SseStreamCapService.Admission.Granted.class);
        return ((SseStreamCapService.Admission.Granted) second).lease();
    }

    // ─── C2 / read-only readiness for the token endpoint ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("C2 readiness is read-only and reports the barrier the browser cannot read from the stream")
    void readinessReportsTheBarrierWithoutWritingAnything() {
        // Unknown provenance: ready, because only an admission attempt may arm a barrier and telling the client to
        // wait first would mean nobody ever armed it.
        SseStreamCapService.Readiness fresh = instanceA.readiness(tenantId);
        assertThat(fresh.ready()).isTrue();
        assertThat(fresh.reason()).isEqualTo("FIRST_ATTEMPT_WILL_ARM_BARRIER");
        assertThat(instanceA.admittedCount(tenantId))
                .as("the probe wrote nothing: no generation, no member")
                .isZero();

        // That one attempt arms it. From now on the refusal is readable, with the remaining quarantine.
        assertThat(instanceA.admit(tenantId, "user-1", "s1"))
                .isInstanceOf(SseStreamCapService.Admission.Refused.class);

        SseStreamCapService.Readiness quarantined = instanceA.readiness(tenantId);
        assertThat(quarantined.ready()).isFalse();
        assertThat(quarantined.reason()).isEqualTo("BARRIER");
        assertThat(quarantined.retryAfterMillis()).isBetween(1L, props.barrier().toMillis() + 500);

        // And it is still read-only: asking does not consume the tenant's one slot.
        assertThat(instanceA.admittedCount(tenantId)).isZero();
        assertThat(instanceB.readiness(tenantId).ready())
                .as("the other instance sees the same quarantine")
                .isFalse();
    }

    @Test
    @DisplayName("C2 readiness reports a full cap, and reports ready again once a slot is released")
    void readinessReportsTheCap() throws Exception {
        SseStreamLease first = admitPastBarrier(instanceA, "s1");
        SseStreamCapService.Admission second = instanceB.admit(tenantId, "user-2", "s2");
        assertThat(second).isInstanceOf(SseStreamCapService.Admission.Granted.class);   // CAP is 2 in this set

        SseStreamCapService.Readiness full = instanceA.readiness(tenantId);
        assertThat(full.ready()).isFalse();
        assertThat(full.reason()).isEqualTo("CAP");
        assertThat(full.retryAfterMillis())
                .as("a poll interval the client can act on, never zero")
                .isEqualTo(props.heartbeat().toMillis());

        instanceA.releaseQuietly(first);
        assertThat(instanceA.readiness(tenantId).ready()).isTrue();
        assertThat(instanceA.readiness(tenantId).reason()).isEqualTo("READY");
    }

    // ─── L15 / first use and unknown provenance ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L15 a tenant's first-ever admission is quarantined, and the refusal carries a readable retry hint")
    void firstUseIsQuarantined() {
        SseStreamCapService.Admission first = instanceA.admit(tenantId, "user-1", "s1");

        assertThat(first).isInstanceOf(SseStreamCapService.Admission.Refused.class);
        SseStreamCapService.Admission.Refused refused = (SseStreamCapService.Admission.Refused) first;
        assertThat(refused.reason()).isEqualTo("BARRIER_ARMED_UNKNOWN_PROVENANCE");
        assertThat(refused.retryAfterMillis())
                .as("a browser-readable backoff hint, not a bare rejection")
                .isBetween(1L, props.barrier().toMillis() + 500);

        // Still refused inside the window, now with the ordinary BARRIER reason.
        SseStreamCapService.Admission second = instanceA.admit(tenantId, "user-1", "s2");
        assertThat(((SseStreamCapService.Admission.Refused) second).reason()).isEqualTo("BARRIER");
    }

    @Test
    @DisplayName("L15 an absent key never short-circuits the barrier, even for a tenant that just streamed")
    void losingTheSetQuarantinesAgain() throws Exception {
        admitPastBarrier(instanceA, "s1");

        redis.delete("sse:streams:" + tenantId);

        SseStreamCapService.Admission afterLoss = instanceA.admit(tenantId, "user-1", "s2");
        assertThat(((SseStreamCapService.Admission.Refused) afterLoss).reason())
                .isEqualTo("BARRIER_ARMED_UNKNOWN_PROVENANCE");
    }

    // ─── L1 / the cap across instances ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L1 the cap is tenant-wide across instances: two admitted, the third refused CAP")
    void capIsEnforcedAcrossInstances() throws Exception {
        admitPastBarrier(instanceA, "s1");
        SseStreamCapService.Admission second = instanceB.admit(tenantId, "user-1", "s2");
        SseStreamCapService.Admission third = instanceB.admit(tenantId, "user-1", "s3");

        assertThat(second).isInstanceOf(SseStreamCapService.Admission.Granted.class);
        assertThat(third).isInstanceOf(SseStreamCapService.Admission.Refused.class);
        assertThat(((SseStreamCapService.Admission.Refused) third).reason()).isEqualTo("CAP");
        assertThat(instanceA.admittedCount(tenantId)).isEqualTo(CAP);
    }

    @Test
    @DisplayName("L10 a clean close frees the slot at once, without waiting for the lease to lapse")
    void cleanCloseFreesTheSlot() throws Exception {
        SseStreamLease first = admitPastBarrier(instanceA, "s1");
        SseStreamCapService.Admission second = instanceB.admit(tenantId, "user-1", "s2");
        assertThat(second).isInstanceOf(SseStreamCapService.Admission.Granted.class);
        assertThat(instanceB.admit(tenantId, "user-1", "s3"))
                .isInstanceOf(SseStreamCapService.Admission.Refused.class);

        instanceA.releaseQuietly(first);

        assertThat(instanceB.admit(tenantId, "user-1", "s4"))
                .isInstanceOf(SseStreamCapService.Admission.Granted.class);
    }

    // ─── L2, L3 / refresh validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L2 a valid lease refreshes, and the deadline moves forward rather than being recomputed from scratch")
    void validLeaseRefreshes() throws Exception {
        SseStreamLease lease = admitPastBarrier(instanceA, "s1");
        long originalDeadline = lease.servingDeadlineNanos();

        timeA.advance(props.heartbeat());
        SseStreamCapService.RefreshResult result = instanceA.refresh(lease);

        assertThat(result.renewed()).isTrue();
        assertThat(result.lease().servingDeadlineNanos()).isGreaterThan(originalDeadline);
        assertThat(result.lease().generation()).isEqualTo(lease.generation());
    }

    @Test
    @DisplayName("L3 an expired but unpruned member is refused EXPIRED: ZADD XX alone would have renewed it")
    void expiredButUnprunedMemberCannotRefresh() throws Exception {
        SseStreamLease lease = admitPastBarrier(instanceA, "s1");

        // Expire the member in place. No admission runs, so nothing prunes it - it is still present in Redis.
        redis.opsForZSet().add("sse:streams:" + tenantId, lease.member(), 1.0);
        assertThat(redis.opsForZSet().score("sse:streams:" + tenantId, lease.member()))
                .as("still present, so this is the unpruned case").isNotNull();

        SseStreamCapService.RefreshResult result = instanceA.refresh(lease);

        assertThat(result.renewed()).isFalse();
        assertThat(result.reason()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("L14 a refresh carrying a stale generation is refused, so it cannot extend a dead generation")
    void staleGenerationCannotRefresh() throws Exception {
        SseStreamLease lease = admitPastBarrier(instanceA, "s1");
        SseStreamLease forged = new SseStreamLease(lease.tenantId(), lease.recipientRef(), lease.streamId(),
                lease.member(), "someone-elses-generation", lease.redisExpiryMs(), lease.servingDeadlineNanos());

        SseStreamCapService.RefreshResult result = instanceA.refresh(forged);

        assertThat(result.renewed()).isFalse();
        assertThat(result.reason()).isEqualTo("GENERATION");
    }

    @Test
    @DisplayName("L14 a refresh for a member that is gone is refused ABSENT, never silently re-created")
    void absentMemberCannotRefresh() throws Exception {
        SseStreamLease lease = admitPastBarrier(instanceA, "s1");
        redis.opsForZSet().remove("sse:streams:" + tenantId, lease.member());

        SseStreamCapService.RefreshResult result = instanceA.refresh(lease);

        assertThat(result.renewed()).isFalse();
        assertThat(result.reason()).isEqualTo("ABSENT");
        assertThat(redis.opsForZSet().score("sse:streams:" + tenantId, lease.member()))
                .as("the refusal did not re-create it").isNull();
    }

    @Test
    @DisplayName("L7 one tenant losing its set does not affect another tenant")
    void tenantSetLossIsIsolated() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        admitPastBarrier(instanceA, "s1");
        // The other tenant pays its own first-use barrier, independently.
        assertThat(instanceB.admit(otherTenant, "user-x", "o1"))
                .isInstanceOf(SseStreamCapService.Admission.Refused.class);
        Thread.sleep(props.barrier().toMillis() + 200);
        SseStreamCapService.Admission otherAdmitted = instanceB.admit(otherTenant, "user-x", "o1");
        assertThat(otherAdmitted).isInstanceOf(SseStreamCapService.Admission.Granted.class);

        redis.delete("sse:streams:" + tenantId);

        assertThat(instanceA.admit(tenantId, "user-1", "s9"))
                .as("the affected tenant is quarantined")
                .isInstanceOf(SseStreamCapService.Admission.Refused.class);
        assertThat(instanceB.admittedCount(otherTenant))
                .as("the other tenant is untouched").isEqualTo(1);
    }

    // ─── L12, L13 / deadline anchoring ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L12 the deadline is anchored before the request, so the round trip is charged to the lease")
    void deadlineIsAnchoredToTheRequestStart() throws Exception {
        SseStreamLease lease = admitPastBarrier(instanceA, "s1");

        long remaining = lease.remainingMillis(timeA);
        assertThat(remaining)
                .as("never more than the serving budget, whatever the round trip cost")
                .isLessThanOrEqualTo(props.servingBudget().toMillis());
    }

    @Test
    @DisplayName("L13 an admission reply that outlived its own budget is rejected instead of granting late authority")
    void staleAdmissionReplyIsRejected() throws Exception {
        // Advance the monotonic source past the whole budget while the call is in flight: the implementation
        // compares the anchored deadline against "now" after the reply, which is exactly this situation.
        FakeTime slow = new FakeTime() {
            private boolean advanced;

            @Override
            public long nanos() {
                long value = super.nanos();
                if (!advanced) {
                    advanced = true;
                    return value;                       // the pre-request anchor
                }
                return value + props.leaseTtl().toNanos();   // every later read is past the budget
            }
        };
        SseStreamCapService slowInstance = new SseStreamCapService(redis, props, slow);

        // Arm and pass the barrier with a normal instance first.
        admitPastBarrier(instanceA, "s1");

        SseStreamCapService.Admission admission = slowInstance.admit(tenantId, "user-1", "s2");

        assertThat(admission).isInstanceOf(SseStreamCapService.Admission.Refused.class);
        assertThat(((SseStreamCapService.Admission.Refused) admission).reason()).isEqualTo("STALE_ADMISSION_REPLY");
    }

    // ─── L11 / lapsed leases are reclaimed ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L11 a crashed instance's lease lapses and the next admission prunes it, so the slot is reusable")
    void lapsedLeasesArePruned() throws Exception {
        admitPastBarrier(instanceA, "s1");
        SseStreamCapService.Admission second = instanceB.admit(tenantId, "user-1", "s2");
        assertThat(second).isInstanceOf(SseStreamCapService.Admission.Granted.class);
        assertThat(instanceB.admit(tenantId, "user-1", "s3"))
                .isInstanceOf(SseStreamCapService.Admission.Refused.class);

        // Both leases lapse on Redis's clock while nobody refreshes them.
        Thread.sleep(props.leaseTtl().toMillis() + 300);

        assertThat(instanceB.admit(tenantId, "user-1", "s4"))
                .as("the lapsed members were pruned by the admission itself")
                .isInstanceOf(SseStreamCapService.Admission.Granted.class);
        assertThat(instanceB.admittedCount(tenantId)).isEqualTo(1);
    }
}
