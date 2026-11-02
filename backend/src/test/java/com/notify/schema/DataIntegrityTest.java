package com.notify.schema;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.*;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.model.NotificationTransitioner;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import com.notify.template.entity.TemplateEntity;
import com.notify.template.entity.TemplateVersionEntity;
import com.notify.template.repository.TemplateRepository;
import com.notify.template.repository.TemplateVersionRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataIntegrityTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private TemplateVersionRepository templateVersionRepository;

    @Autowired
    private NotificationTransitioner notificationTransitioner;

    private TenantEntity tenantA;
    private TenantEntity tenantB;

    @BeforeEach
    void setUp() {
        tenantA = new TenantEntity();
        tenantA.setName("Tenant Alpha");
        tenantA.setSlug("tenant-alpha-" + UUID.randomUUID());
        tenantA.setStatus(TenantStatus.ACTIVE);
        tenantA = tenantRepository.save(tenantA);

        tenantB = new TenantEntity();
        tenantB.setName("Tenant Beta");
        tenantB.setSlug("tenant-beta-" + UUID.randomUUID());
        tenantB.setStatus(TenantStatus.ACTIVE);
        tenantB = tenantRepository.save(tenantB);
    }

    @Test
    @DisplayName("Composite Foreign Key Rejection: cannot link child with mismatched tenant_id")
    void compositeForeignKeyRejectsCrossTenantAssociation() {
        // Create notification belonging to Tenant A
        NotificationEntity notification = new NotificationEntity();
        notification.setTenantId(tenantA.getId());
        notification.setChannel(Channel.EMAIL);
        notification.setStatus(NotificationStatus.CREATED);
        notification.setRecipient("user@example.com");
        notification.setBody("Hello from Alpha");
        NotificationEntity savedNotification = notificationRepository.save(notification);

        // Attempt to insert status history for this notification using Tenant B's tenant_id
        NotificationStatusHistoryEntity forgedHistory = new NotificationStatusHistoryEntity();
        forgedHistory.setTenantId(tenantB.getId()); // MISMATCHED TENANT
        forgedHistory.setNotificationId(savedNotification.getId());
        forgedHistory.setToStatus(NotificationStatus.QUEUED);
        forgedHistory.setActor(Actor.SYSTEM);
        forgedHistory.setOccurredAt(Instant.now());

        assertThatThrownBy(() -> {
            statusHistoryRepository.saveAndFlush(forgedHistory);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Idempotency Constraint: duplicate (tenant_id, idempotency_key) rejected, cross-tenant allowed")
    void idempotencyConstraintEnforcedPerTenant() {
        String sharedKey = "idem-key-" + UUID.randomUUID();

        // First notification for Tenant A succeeds
        NotificationEntity n1 = new NotificationEntity();
        n1.setTenantId(tenantA.getId());
        n1.setChannel(Channel.EMAIL);
        n1.setStatus(NotificationStatus.CREATED);
        n1.setRecipient("a@example.com");
        n1.setBody("Body 1");
        n1.setIdempotencyKey(sharedKey);
        notificationRepository.saveAndFlush(n1);

        // Second notification for Tenant A with same idempotency key fails
        NotificationEntity n2 = new NotificationEntity();
        n2.setTenantId(tenantA.getId());
        n2.setChannel(Channel.EMAIL);
        n2.setStatus(NotificationStatus.CREATED);
        n2.setRecipient("a@example.com");
        n2.setBody("Body 2");
        n2.setIdempotencyKey(sharedKey);

        assertThatThrownBy(() -> {
            notificationRepository.saveAndFlush(n2);
        }).isInstanceOf(DataIntegrityViolationException.class);

        // Same idempotency key for Tenant B succeeds
        NotificationEntity n3 = new NotificationEntity();
        n3.setTenantId(tenantB.getId());
        n3.setChannel(Channel.EMAIL);
        n3.setStatus(NotificationStatus.CREATED);
        n3.setRecipient("b@example.com");
        n3.setBody("Body 3");
        n3.setIdempotencyKey(sharedKey);

        NotificationEntity savedB = notificationRepository.saveAndFlush(n3);
        assertThat(savedB.getId()).isNotNull();
    }

    @Test
    @DisplayName("Template Version Unique Constraint: (template_id, version_number) unique")
    void templateVersionNumberUniquePerTemplate() {
        TemplateEntity template = new TemplateEntity();
        template.setTenantId(tenantA.getId());
        template.setName("Welcome Email");
        template.setChannel(Channel.EMAIL);
        template = templateRepository.saveAndFlush(template);

        TemplateVersionEntity v1 = new TemplateVersionEntity();
        v1.setTenantId(tenantA.getId());
        v1.setTemplateId(template.getId());
        v1.setVersionNumber(1);
        v1.setStatus(TemplateVersionStatus.DRAFT);
        v1.setBody("Hello World v1");
        templateVersionRepository.saveAndFlush(v1);

        TemplateVersionEntity duplicateV1 = new TemplateVersionEntity();
        duplicateV1.setTenantId(tenantA.getId());
        duplicateV1.setTemplateId(template.getId());
        duplicateV1.setVersionNumber(1);
        duplicateV1.setStatus(TemplateVersionStatus.DRAFT);
        duplicateV1.setBody("Hello World duplicate");

        assertThatThrownBy(() -> {
            templateVersionRepository.saveAndFlush(duplicateV1);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("NotificationTransitioner: valid transition updates status and writes history")
    void notificationTransitionerSucceedsOnValidTransition() {
        NotificationEntity notif = new NotificationEntity();
        notif.setTenantId(tenantA.getId());
        notif.setChannel(Channel.EMAIL);
        notif.setStatus(NotificationStatus.CREATED);
        notif.setRecipient("transition@example.com");
        notif.setBody("Test transition");
        notif.setDispatchGeneration(0);
        notif = notificationRepository.saveAndFlush(notif);

        notificationTransitioner.transition(
                tenantA.getId(),
                notif.getId(),
                NotificationStatus.CREATED,
                NotificationStatus.QUEUED,
                Actor.SYSTEM,
                0,
                "Immediate queueing"
        );

        NotificationEntity updated = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.QUEUED);

        List<NotificationStatusHistoryEntity> history =
                statusHistoryRepository.findByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantA.getId(), notif.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFromStatus()).isEqualTo(NotificationStatus.CREATED);
        assertThat(history.get(0).getToStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(history.get(0).getActor()).isEqualTo(Actor.SYSTEM);
    }

    @Test
    @DisplayName("NotificationTransitioner: fails when dispatch generation does not match")
    void notificationTransitionerFailsOnStaleGeneration() {
        NotificationEntity notif = new NotificationEntity();
        notif.setTenantId(tenantA.getId());
        notif.setChannel(Channel.EMAIL);
        notif.setStatus(NotificationStatus.CREATED);
        notif.setRecipient("stale@example.com");
        notif.setBody("Test stale");
        notif.setDispatchGeneration(0);
        NotificationEntity savedNotif = notificationRepository.saveAndFlush(notif);

        assertThatThrownBy(() -> {
            notificationTransitioner.transition(
                    tenantA.getId(),
                    savedNotif.getId(),
                    NotificationStatus.CREATED,
                    NotificationStatus.QUEUED,
                    Actor.SYSTEM,
                    99, // WRONG GENERATION
                    "Stale update attempt"
            );
        }).isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(ne.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        NotificationEntity unchanged = notificationRepository.findById(savedNotif.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(NotificationStatus.CREATED);
    }
}
