package com.notify.delivery.recovery;

import com.notify.common.model.Channel;
import com.notify.notification.dto.StuckClaimCandidate;
import com.notify.notification.model.NotificationTransitioner.ReapOutcome;
import com.notify.notification.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What one recovery scan reports to an operator (/ , 's recovery half; repair of review finding F5).
 *
 * <p>The reaper's own counters - {@code notify.recovery.reaped} by outcome and {@code notify.recovery.errors} -
 * were the only signal that recovery is running and what it decided, and nothing asserted them. Each outcome is
 * driven here through a controlled service, including the one that throws: a single unreapable candidate must be
 * counted as an error and must not abandon the rest of the scan.
 */
class ReaperMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private NotificationService notificationService;
    private SimpleMeterRegistry meters;
    private StuckProcessingReaper reaper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        meters = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(meters);
        reaper = new StuckProcessingReaper(
                notificationService,
                new ReaperProperties(true, Duration.ofMinutes(10), Duration.ofMinutes(1), 100, 10),
                CLOCK,
                provider);
    }

    private static StuckClaimCandidate candidate(int claimSeq) {
        return new StuckClaimCandidate(UUID.randomUUID(), UUID.randomUUID(), Channel.EMAIL,
                0, claimSeq, 1, 5, NOW.minus(Duration.ofMinutes(30)));
    }

    private double counter(String outcome) {
        io.micrometer.core.instrument.Counter found =
                meters.find("notify.recovery.reaped").tag("outcome", outcome).counter();
        return found == null ? 0 : found.count();
    }

    private double errors() {
        io.micrometer.core.instrument.Counter found = meters.find("notify.recovery.errors").counter();
        return found == null ? 0 : found.count();
    }

    @Test
    @DisplayName("each outcome is counted under its own bounded tag")
    void everyOutcomeIsCounted() {
        StuckClaimCandidate rescheduled = candidate(1);
        StuckClaimCandidate exhausted = candidate(2);
        StuckClaimCandidate movedOn = candidate(3);
        when(notificationService.findStuckProcessingCandidates(any(), eq(null), eq(null), anyInt()))
                .thenReturn(List.of(rescheduled, exhausted, movedOn));
        when(notificationService.findStuckProcessingCandidates(any(), any(Instant.class), any(UUID.class), anyInt()))
                .thenReturn(List.of());
        when(notificationService.recoverStuckProcessing(
                eq(rescheduled.tenantId()), eq(rescheduled.id()), anyInt(), anyInt(), any()))
                .thenReturn(ReapOutcome.RESCHEDULED);
        when(notificationService.recoverStuckProcessing(
                eq(exhausted.tenantId()), eq(exhausted.id()), anyInt(), anyInt(), any()))
                .thenReturn(ReapOutcome.EXHAUSTED);
        when(notificationService.recoverStuckProcessing(
                eq(movedOn.tenantId()), eq(movedOn.id()), anyInt(), anyInt(), any()))
                .thenReturn(ReapOutcome.NOT_STUCK);

        StuckProcessingReaper.ScanReport report = reaper.scan();

        assertThat(report.examined()).isEqualTo(3);
        assertThat(report.rescheduled()).isEqualTo(1);
        assertThat(report.exhausted()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.reaped()).isEqualTo(2);

        assertThat(counter("rescheduled")).isEqualTo(1);
        assertThat(counter("exhausted")).isEqualTo(1);
        assertThat(counter("skipped")).isEqualTo(1);
        assertThat(errors()).isZero();
        assertThat(meters.getMeters())
                .as("three fixed outcome tags, no per-notification cardinality")
                .hasSize(3);
    }

    @Test
    @DisplayName("one unreapable candidate is counted as an error and the scan carries on")
    void anErrorIsCountedAndTheScanContinues() {
        StuckClaimCandidate failing = candidate(1);
        StuckClaimCandidate healthy = candidate(2);
        when(notificationService.findStuckProcessingCandidates(any(), eq(null), eq(null), anyInt()))
                .thenReturn(List.of(failing, healthy));
        when(notificationService.findStuckProcessingCandidates(any(), any(Instant.class), any(UUID.class), anyInt()))
                .thenReturn(List.of());
        when(notificationService.recoverStuckProcessing(
                eq(failing.tenantId()), eq(failing.id()), anyInt(), anyInt(), any()))
                .thenThrow(new CannotAcquireLockException("row is locked"));
        when(notificationService.recoverStuckProcessing(
                eq(healthy.tenantId()), eq(healthy.id()), anyInt(), anyInt(), any()))
                .thenReturn(ReapOutcome.RESCHEDULED);

        StuckProcessingReaper.ScanReport report = reaper.scan();

        assertThat(errors()).as("the failure is visible to an operator").isEqualTo(1);
        assertThat(report.rescheduled())
                .as("the candidate after the failing one was still reaped")
                .isEqualTo(1);
        assertThat(report.examined()).isEqualTo(2);
    }

    @Test
    @DisplayName("a disabled reaper counts nothing and reads nothing")
    void aDisabledReaperDoesNothing() {
        StuckProcessingReaper disabled = new StuckProcessingReaper(
                notificationService,
                new ReaperProperties(false, Duration.ofMinutes(10), Duration.ofMinutes(1), 100, 10),
                CLOCK,
                providerOf(meters));

        StuckProcessingReaper.ScanReport report = disabled.scan();

        assertThat(report.examined()).isZero();
        assertThat(meters.getMeters()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(registry);
        return provider;
    }
}
