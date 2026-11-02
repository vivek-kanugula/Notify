package com.notify.sse.stream;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.common.model.TenantStatus;
import com.notify.inapp.model.InAppMessageView;
import com.notify.inapp.service.InAppMessageService;
import com.notify.sse.config.SseProperties;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resync / cursor handover with more than one hundred missed messages (corrective cycle, finding F3).
 *
 * <p><b>What was wrong.</b> The server emits {@code resync} <em>before</em> sending the overflowing rows and
 * <em>before</em> advancing its cursor - that part was correct. The client then reloaded its REST list but
 * reconnected with the <em>same</em> {@code lastEventId}, so the server immediately found the same backlog and
 * emitted {@code resync} again: an endless loop in which the cursor never left "0". The server half of the handover
 * is asserted here against real PostgreSQL; the client half (pause, recover, resume with the recovered cursor) is
 * asserted in {@code frontend/test/streamLoop.test.ts}, and the two meet in the browser journey.
 *
 * <p>The cap lease is stubbed on purpose: nothing here is about admission, and INV-CAP has its own Redis-backed
 * suite. The advisory lock and commit ordering likewise belong to {@code InAppPersistenceIntegrationTest}, so the
 * rows are seeded directly.
 */
class InAppStreamResyncIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int BACKLOG = 101;                 // one more than the catch-up budget
    private static final int REST_PAGE = 25;

    @Autowired private InAppMessageService messages;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private final String recipient = "resync-user";
    private MonotonicTimeSource time;
    private SseStreamRegistry registry;
    private ScheduledExecutorService timers;
    private ExecutorService dispatcher;
    private ExecutorService maintenance;

    /** Records the frames a stream writes, flattened to strings so ids and event names can be asserted. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> frames = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
        }

        List<String> messageIds() {
            List<String> ids = new ArrayList<>();
            for (String frame : frames) {
                if (!frame.contains("event:message")) {
                    continue;
                }
                int start = frame.indexOf("id:") + 3;
                ids.add(frame.substring(start, frame.indexOf('\n', start)).trim());
            }
            return ids;
        }

        List<String> resyncFrames() {
            return frames.stream().filter(frame -> frame.contains("event:resync")).toList();
        }
    }

    private static final class StubCap extends SseStreamCapService {
        private final MonotonicTimeSource time;

        StubCap(SseProperties props, MonotonicTimeSource time) {
            super(null, props, time);
            this.time = time;
        }

        @Override
        public Admission admit(UUID tenantId, String recipientRef, String streamId) {
            return new Admission.Granted(new SseStreamLease(tenantId, recipientRef, streamId,
                    "m:" + streamId, "gen", System.currentTimeMillis() + 90_000,
                    time.nanos() + Duration.ofMinutes(5).toNanos()));
        }

        @Override
        public RefreshResult refresh(SseStreamLease lease) {
            return new RefreshResult(lease, "OK");
        }

        @Override
        public void releaseQuietly(SseStreamLease lease) {
        }
    }

    private static SseProperties props() {
        return new SseProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(25), Duration.ofSeconds(8), Duration.ofSeconds(5),
                Duration.ofSeconds(95), Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofSeconds(60),
                100, 200, 64, 2);
    }

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.save(
                new TenantEntity("ResyncOrg", "resync-org-" + UUID.randomUUID())).getId();
        time = new MonotonicTimeSource();
        timers = Executors.newScheduledThreadPool(1);
        dispatcher = Executors.newFixedThreadPool(2);
        maintenance = Executors.newFixedThreadPool(2);
        ApiKeyStatusCacheService alwaysActive = new ApiKeyStatusCacheService(null, null, null) {
            @Override
            public Optional<TenantStatus> getTenantStatus(UUID id) {
                return Optional.of(TenantStatus.ACTIVE);
            }
        };
        registry = new SseStreamRegistry(new StubCap(props(), time), messages, alwaysActive, props(), time,
                new SimpleMeterRegistry(), timers, dispatcher, maintenance);
    }

    @AfterEach
    void tearDown() {
        timers.shutdownNow();
        dispatcher.shutdownNow();
        maintenance.shutdownNow();
    }

    /** Seeds {@code count} delivered in-app messages for the recipient and returns their sequences, ascending. */
    private List<Long> seed(int count, String subjectPrefix) {
        List<Long> seqs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID notificationId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO notifications (id, tenant_id, channel, status, recipient, subject, body,
                        dispatch_generation, delivery_cycle, attempt_count, max_attempts, queued_at)
                    VALUES (?, ?, 'IN_APP', 'DELIVERED', ?, ?, ?, 0, 1, 1, 5, now())
                    """, notificationId, tenantId, recipient, subjectPrefix + " " + i, "Body " + i);
            Long seq = jdbc.queryForObject("""
                    INSERT INTO inapp_messages (tenant_id, notification_id, recipient_ref, subject, body, created_at)
                    VALUES (?, ?, ?, ?, ?, now()) RETURNING seq
                    """, Long.class, tenantId, notificationId, recipient, subjectPrefix + " " + i, "Body " + i);
            seqs.add(seq);
        }
        return seqs;
    }

    private String streamId() {
        return registry.sessionIds().iterator().next();
    }

    @Test
    @DisplayName("F3 a backlog over the budget resyncs without advancing the cursor, then resumes and keeps flowing")
    void aLargeBacklogIsHandedOverAndTheStreamResumes() throws Exception {
        List<Long> backlog = seed(BACKLOG, "Backlog");
        assertThat(backlog).hasSize(BACKLOG);

        // 1. A stream opening at cursor 0 finds 101 eligible rows: one more than the catch-up budget.
        RecordingEmitter first = new RecordingEmitter();
        registry.openWith(first, tenantId, recipient, 0L);
        String firstId = streamId();
        InAppStreamSession firstSession = registry.session(firstId).orElseThrow();

        assertThat(first.messageIds()).as("no row is emitted before the client has caught up by REST").isEmpty();
        assertThat(first.resyncFrames()).hasSize(1);
        assertThat(first.resyncFrames().get(0))
                .contains("GAP_TOO_LARGE")
                .contains("fromCursor=0");
        assertThat(firstSession.cursor()).as("the cursor did not move: that is why resuming on it looped").isZero();
        assertThat(firstSession.isStopped()).isTrue();
        registry.close(firstId, "client reconnecting");

        // 2. The client's recovery: the approved paginated read, newest first, until it reaches the stream's cursor.
        List<Long> recovered = new ArrayList<>();
        Long before = null;
        int pages = 0;
        while (pages < 20) {
            List<InAppMessageView> page = messages.page(tenantId, recipient, before, REST_PAGE + 1);
            boolean hasMore = page.size() > REST_PAGE;
            List<InAppMessageView> rows = hasMore ? page.subList(0, REST_PAGE) : page;
            rows.forEach(row -> recovered.add(row.seq()));
            pages++;
            if (!hasMore) {
                break;
            }
            before = rows.get(rows.size() - 1).seq();
        }

        assertThat(recovered).as("the recovery skipped no eligible message").hasSize(BACKLOG);
        assertThat(recovered).doesNotHaveDuplicates();
        assertThat(recovered).containsExactlyInAnyOrderElementsOf(backlog);
        long recoveryCursor = recovered.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(recoveryCursor)
                .as("a sequence actually read, never an invented latest value")
                .isEqualTo(backlog.get(BACKLOG - 1));

        // 3. Reconnecting with the recovered cursor resolves the condition instead of reproducing it.
        RecordingEmitter second = new RecordingEmitter();
        registry.openWith(second, tenantId, recipient, recoveryCursor);
        String secondId = streamId();
        InAppStreamSession secondSession = registry.session(secondId).orElseThrow();

        assertThat(second.resyncFrames()).as("the loop is broken: no second resync").isEmpty();
        assertThat(second.messageIds()).isEmpty();
        assertThat(secondSession.isStopped()).isFalse();
        assertThat(secondSession.cursor()).isEqualTo(recoveryCursor);

        // 4. New messages after the handover arrive live, in ascending order, with nothing skipped.
        List<Long> fresh = seed(3, "After recovery");
        registry.onHint(tenantId, recipient);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (second.messageIds().size() < 3 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(25);
        }

        assertThat(second.messageIds())
                .containsExactly(fresh.stream().map(String::valueOf).toArray(String[]::new));
        assertThat(secondSession.cursor()).isEqualTo(fresh.get(2));
        assertThat(second.resyncFrames()).isEmpty();

        // 5. And it stays settled: another wake-up emits nothing and never resyncs.
        registry.onHint(tenantId, recipient);
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(second.messageIds()).hasSize(3);
        assertThat(second.resyncFrames()).isEmpty();
        assertThat(registry.session(secondId)).isPresent();
    }

    @Test
    @DisplayName("F3 a backlog exactly at the budget is delivered in full, so the resync boundary is the 101st row")
    void aBacklogAtTheBudgetIsDeliveredWithoutResync() {
        List<Long> backlog = seed(100, "At budget");

        RecordingEmitter emitter = new RecordingEmitter();
        registry.openWith(emitter, tenantId, recipient, 0L);
        InAppStreamSession session = registry.session(streamId()).orElseThrow();

        assertThat(emitter.resyncFrames()).isEmpty();
        assertThat(emitter.messageIds())
                .containsExactly(backlog.stream().map(String::valueOf).toArray(String[]::new));
        assertThat(session.cursor()).isEqualTo(backlog.get(99));
    }
}
