package com.notify.notification;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.CreateNotificationRequest;
import com.notify.notification.event.NotificationTransitionedEvent;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.service.NotificationService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(NotificationEventTransactionalListenerTest.ListenerConfig.class)
class NotificationEventTransactionalListenerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationTransitioner notificationTransitioner;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestAfterCommitListener listener;

    private UUID tenantId;

    @TestConfiguration
    static class ListenerConfig {
        @Bean
        public TestAfterCommitListener testAfterCommitListener() {
            return new TestAfterCommitListener();
        }
    }

    static class TestAfterCommitListener {
        final List<NotificationTransitionedEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onTransition(NotificationTransitionedEvent event) {
            events.add(event);
        }

        public void reset() {
            events.clear();
        }
    }

    @BeforeEach
    void setUp() {
        listener.reset();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity("Event Test Tenant " + suffix, "event-tenant-" + suffix);
        tenant.setStatus(com.notify.common.model.TenantStatus.ACTIVE);
        tenant = tenantRepository.save(tenant);
        tenantId = tenant.getId();
    }

    @Test
    @DisplayName("Notification creation publishes NotificationTransitionedEvent observed once by AFTER_COMMIT listener")
    void createNotification_observedOnceAfterCommit() {
        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Event Test",
                "Body content",
                null,
                null,
                null,
                null,
                null,
                null
        );

        NotificationService.AcceptanceResult result = notificationService.acceptNotification(tenantId, req, null);
        assertThat(result).isNotNull();

        // Exactly one event observed after commit
        assertThat(listener.events).hasSize(1);
        NotificationTransitionedEvent event = listener.events.get(0);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.notificationId()).isEqualTo(result.notificationId());
        assertThat(event.fromStatus()).isEqualTo(NotificationStatus.CREATED);
        assertThat(event.toStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(event.actor()).isEqualTo(Actor.SYSTEM);
        assertThat(event.generation()).isEqualTo(1);
    }

    @Test
    @DisplayName("Notification cancellation publishes NotificationTransitionedEvent observed once by AFTER_COMMIT listener")
    void cancelNotification_observedOnceAfterCommit() {
        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Cancel Event Test",
                "Body content",
                null,
                null,
                null,
                null,
                null,
                null
        );

        NotificationService.AcceptanceResult createResult = notificationService.acceptNotification(tenantId, req, null);
        listener.reset();

        notificationService.cancelNotification(tenantId, createResult.notificationId(), "Test cancellation");

        assertThat(listener.events).hasSize(1);
        NotificationTransitionedEvent event = listener.events.get(0);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.notificationId()).isEqualTo(createResult.notificationId());
        assertThat(event.fromStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(event.toStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(event.actor()).isEqualTo(Actor.TENANT);
    }

    @Test
    @DisplayName("NotificationTransitioner transitions publish NotificationTransitionedEvent observed once by AFTER_COMMIT listener")
    void transitioner_observedOnceAfterCommit() {
        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Transitioner Event Test",
                "Body content",
                null,
                null,
                null,
                null,
                null,
                null
        );

        NotificationService.AcceptanceResult createResult = notificationService.acceptNotification(tenantId, req, null);
        listener.reset();

        notificationTransitioner.transition(
                tenantId,
                createResult.notificationId(),
                NotificationStatus.QUEUED,
                NotificationStatus.PROCESSING,
                Actor.SYSTEM,
                1,
                "Worker claiming"
        );

        assertThat(listener.events).hasSize(1);
        NotificationTransitionedEvent event = listener.events.get(0);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.notificationId()).isEqualTo(createResult.notificationId());
        assertThat(event.fromStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(event.toStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(event.actor()).isEqualTo(Actor.SYSTEM);
        assertThat(event.generation()).isEqualTo(1);
    }

    @Test
    @DisplayName("Transaction rollback emits zero AFTER_COMMIT events")
    void rollback_emitsNoAfterCommitEvents() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> {
            txTemplate.execute(status -> {
                CreateNotificationRequest req = new CreateNotificationRequest(
                        Channel.EMAIL,
                        "user@example.com",
                        "Rollback Event Test",
                        "Body content",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
                notificationService.acceptNotification(tenantId, req, null);
                // Force a transaction rollback
                throw new RuntimeException("Simulated transaction rollback");
            });
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated transaction rollback");

        // The AFTER_COMMIT listener must receive ZERO events
        assertThat(listener.events).isEmpty();
    }
}
