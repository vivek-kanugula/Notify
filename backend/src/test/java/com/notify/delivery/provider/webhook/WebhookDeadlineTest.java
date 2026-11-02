package com.notify.delivery.provider.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7: the budget arithmetic. Every phase timeout is derived from what is left, so the worst case of an attempt is the
 * budget rather than the sum of the phases.
 */
class WebhookDeadlineTest {

    @Test
    @DisplayName("a fresh budget reports the whole budget as remaining and is not expired")
    void freshBudget() {
        WebhookDeadline deadline = WebhookDeadline.startingNow(Duration.ofSeconds(5));

        assertThat(deadline.expired()).isFalse();
        assertThat(deadline.remainingMs()).isBetween(4_900L, 5_000L);
        assertThat(deadline.elapsedMs()).isLessThan(100L);
    }

    @Test
    @DisplayName("a budget already spent is expired, and remaining never goes negative")
    void spentBudget() {
        WebhookDeadline deadline = WebhookDeadline.startedAgo(Duration.ofSeconds(5), Duration.ofSeconds(6));

        assertThat(deadline.expired()).isTrue();
        assertThat(deadline.remainingMs()).isZero();
        assertThat(deadline.elapsedMs()).isGreaterThanOrEqualTo(6_000L);
    }

    @Test
    @DisplayName("a phase gets its configured ceiling only while the budget can still afford it")
    void phaseTimeoutIsCappedByTheBudget() {
        WebhookDeadline fresh = WebhookDeadline.startingNow(Duration.ofSeconds(5));
        assertThat(fresh.phaseTimeoutMs(2_000)).isEqualTo(2_000);

        // 4.2 s of a 5 s budget already spent by name resolution: a 2 s connect ceiling shrinks to what is left.
        WebhookDeadline late = WebhookDeadline.startedAgo(Duration.ofSeconds(5), Duration.ofMillis(4_200));
        assertThat(late.phaseTimeoutMs(2_000)).isBetween(700L, 800L);
    }

    @Test
    @DisplayName("a phase timeout is never zero or negative, even on an exhausted budget")
    void phaseTimeoutHasAFloor() {
        WebhookDeadline spent = WebhookDeadline.startedAgo(Duration.ofSeconds(5), Duration.ofSeconds(9));

        assertThat(spent.phaseTimeoutMs(2_000)).isEqualTo(1L);
        assertThat(spent.connectTimeoutMs(2_000, 4)).isEqualTo(1L);
    }

    @Test
    @DisplayName("the connect share is divided across the resolved addresses, so trying them all stays in budget")
    void connectShareIsDividedAcrossAddresses() {
        WebhookDeadline deadline = WebhookDeadline.startingNow(Duration.ofSeconds(5));

        assertThat(deadline.connectTimeoutMs(2_000, 1)).isEqualTo(2_000);
        assertThat(deadline.connectTimeoutMs(2_000, 2)).isEqualTo(1_000);
        assertThat(deadline.connectTimeoutMs(2_000, 4)).isEqualTo(500);
        // Four blackholed addresses cannot spend more than the whole connect ceiling between them.
        assertThat(deadline.connectTimeoutMs(2_000, 4) * 4).isLessThanOrEqualTo(2_000);
    }

    @Test
    @DisplayName("an empty or absurd address count is treated as one address rather than dividing by zero")
    void addressCountIsDefensive() {
        WebhookDeadline deadline = WebhookDeadline.startingNow(Duration.ofSeconds(5));

        assertThat(deadline.connectTimeoutMs(2_000, 0)).isEqualTo(2_000);
        assertThat(deadline.connectTimeoutMs(2_000, -3)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("the deadline instant is monotonic and matches the budget")
    void deadlineInstantIsMonotonic() {
        long before = System.nanoTime();
        WebhookDeadline deadline = WebhookDeadline.startingNow(Duration.ofSeconds(5));

        assertThat(deadline.deadlineNanos() - before)
                .isBetween(Duration.ofMillis(4_900).toNanos(), Duration.ofMillis(5_100).toNanos());
    }

    @Test
    @DisplayName("a zero budget is expired immediately rather than becoming an unbounded attempt")
    void zeroBudget() {
        WebhookDeadline deadline = WebhookDeadline.startingNow(Duration.ZERO);

        assertThat(deadline.expired()).isTrue();
        assertThat(deadline.remainingMs()).isZero();
    }
}
