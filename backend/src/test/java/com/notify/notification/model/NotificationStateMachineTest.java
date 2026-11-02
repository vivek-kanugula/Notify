package com.notify.notification.model;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Actor;
import com.notify.common.model.NotificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStateMachineTest {

    @Nested
    @DisplayName("Legal Transitions")
    class LegalTransitions {

        @ParameterizedTest(name = "{0} -> {1} by {2} should be valid")
        @CsvSource({
                "CREATED, SCHEDULED, SYSTEM",
                "CREATED, QUEUED, SYSTEM",
                "SCHEDULED, QUEUED, SYSTEM",
                "SCHEDULED, CANCELLED, TENANT",
                "SCHEDULED, CANCELLED, SYSTEM",
                "QUEUED, CANCELLED, TENANT",
                "QUEUED, CANCELLED, SYSTEM",
                "RETRYING, CANCELLED, SYSTEM",
                "RETRYING, QUEUED, SYSTEM",
                "QUEUED, PROCESSING, SYSTEM",
                "RETRYING, PROCESSING, SYSTEM",
                "PROCESSING, DELIVERED, SYSTEM",
                "PROCESSING, RETRYING, SYSTEM",
                "PROCESSING, FAILED, SYSTEM",
                "FAILED, DLQ, SYSTEM",
                "DLQ, QUEUED, ADMIN"
        })
        void shouldAllowLegalTransitions(NotificationStatus from, NotificationStatus to, Actor actor) {
            assertThat(NotificationStateMachine.isValidTransition(from, to, actor)).isTrue();
            // validateTransition should not throw
            NotificationStateMachine.validateTransition(from, to, actor);
        }
    }

    @Nested
    @DisplayName("Illegal Transitions & Actor Restrictions")
    class IllegalTransitions {

        @Test
        @DisplayName("Tenant cannot cancel notification in RETRYING status")
        void tenantCannotCancelRetrying() {
            assertThat(NotificationStateMachine.isValidTransition(
                    NotificationStatus.RETRYING, NotificationStatus.CANCELLED, Actor.TENANT)).isFalse();

            assertThatThrownBy(() -> NotificationStateMachine.validateTransition(
                    NotificationStatus.RETRYING, NotificationStatus.CANCELLED, Actor.TENANT))
                    .isInstanceOf(NotifyException.class)
                    .satisfies(ex -> {
                        NotifyException ne = (NotifyException) ex;
                        assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                        assertThat(ne.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("Tenant cannot cancel notification in PROCESSING status")
        void tenantCannotCancelProcessing() {
            assertThat(NotificationStateMachine.isValidTransition(
                    NotificationStatus.PROCESSING, NotificationStatus.CANCELLED, Actor.TENANT)).isFalse();

            assertThatThrownBy(() -> NotificationStateMachine.validateTransition(
                    NotificationStatus.PROCESSING, NotificationStatus.CANCELLED, Actor.TENANT))
                    .isInstanceOf(NotifyException.class)
                    .satisfies(ex -> {
                        NotifyException ne = (NotifyException) ex;
                        assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                        assertThat(ne.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("System cannot cancel notification in PROCESSING status")
        void systemCannotCancelProcessing() {
            assertThat(NotificationStateMachine.isValidTransition(
                    NotificationStatus.PROCESSING, NotificationStatus.CANCELLED, Actor.SYSTEM)).isFalse();
        }

        @Test
        @DisplayName("Terminal state DELIVERED has no transitions")
        void terminalDeliveredHasNoTransitions() {
            for (NotificationStatus status : NotificationStatus.values()) {
                for (Actor actor : Actor.values()) {
                    assertThat(NotificationStateMachine.isValidTransition(
                            NotificationStatus.DELIVERED, status, actor)).isFalse();
                }
            }
        }

        @Test
        @DisplayName("Terminal state CANCELLED has no transitions")
        void terminalCancelledHasNoTransitions() {
            for (NotificationStatus status : NotificationStatus.values()) {
                for (Actor actor : Actor.values()) {
                    assertThat(NotificationStateMachine.isValidTransition(
                            NotificationStatus.CANCELLED, status, actor)).isFalse();
                }
            }
        }

        @Test
        @DisplayName("DLQ replay can only be initiated by ADMIN, not TENANT or SYSTEM")
        void dlqReplayRestrictedToAdmin() {
            assertThat(NotificationStateMachine.isValidTransition(
                    NotificationStatus.DLQ, NotificationStatus.QUEUED, Actor.TENANT)).isFalse();
            assertThat(NotificationStateMachine.isValidTransition(
                    NotificationStatus.DLQ, NotificationStatus.QUEUED, Actor.SYSTEM)).isFalse();
            assertThat(NotificationStateMachine.isValidTransition(
                    NotificationStatus.DLQ, NotificationStatus.QUEUED, Actor.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("Null arguments return false")
        void nullArgumentsReturnFalse() {
            assertThat(NotificationStateMachine.isValidTransition(null, NotificationStatus.QUEUED, Actor.SYSTEM)).isFalse();
            assertThat(NotificationStateMachine.isValidTransition(NotificationStatus.CREATED, null, Actor.SYSTEM)).isFalse();
            assertThat(NotificationStateMachine.isValidTransition(NotificationStatus.CREATED, NotificationStatus.QUEUED, null)).isFalse();
        }
    }
}
