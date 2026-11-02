package com.notify.notification;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.NotificationResponse;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class NotificationRepositoryProjectionTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = createTenant("Tenant-A").getId();
        tenantB = createTenant("Tenant-B").getId();
    }

    private TenantEntity createTenant(String name) {
        TenantEntity t = new TenantEntity();
        t.setName(name);
        t.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        t.setRateLimitPerMinute(100);
        return tenantRepository.save(t);
    }

    private NotificationEntity createNotification(
            UUID tenantId, Channel channel, NotificationStatus status,
            String recipient, String subject, String body, Instant createdAt
    ) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(channel);
        n.setStatus(status);
        n.setRecipient(recipient);
        n.setSubject(subject);
        n.setBody(body);
        n.setDispatchGeneration(1);
        n.setCreatedAt(createdAt);
        n.setUpdatedAt(createdAt);
        return notificationRepository.save(n);
    }

    @Test
    @DisplayName("Database DTO projection selects only list fields, isolates tenants, and preserves filters and sorting")
    void databaseDtoProjection_selectsOnlyListFields_andEnforcesTenantIsolation() {
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant t1 = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant t2 = Instant.now().minus(1, ChronoUnit.MINUTES);

        // Tenant A rows
        NotificationEntity a1 = createNotification(
                tenantA, Channel.EMAIL, NotificationStatus.QUEUED,
                "alice@example.com", "Email Subject 1", "Full message body A1", t0
        );
        NotificationEntity a2 = createNotification(
                tenantA, Channel.EMAIL, NotificationStatus.DELIVERED,
                "alice@example.com", "Email Subject 2", "Full message body A2", t1
        );
        NotificationEntity a3 = createNotification(
                tenantA, Channel.WEBHOOK, NotificationStatus.QUEUED,
                "https://webhook.example.com", null, "Full webhook payload A3", t2
        );

        // Tenant B rows (must never leak into Tenant A queries)
        createNotification(
                tenantB, Channel.EMAIL, NotificationStatus.QUEUED,
                "bob@example.com", "Tenant B Subject", "Full message body B", t1
        );

        // 1. Tenant-scoped listing with ordering
        PageRequest pageReq = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<NotificationResponse> pageA = notificationRepository.findSummariesByTenantId(tenantA, pageReq);

        assertThat(pageA.getTotalElements()).isEqualTo(3);
        assertThat(pageA.getContent()).hasSize(3);
        // Ordered newest first
        assertThat(pageA.getContent().get(0).id()).isEqualTo(a3.getId());
        assertThat(pageA.getContent().get(1).id()).isEqualTo(a2.getId());
        assertThat(pageA.getContent().get(2).id()).isEqualTo(a1.getId());

        // Check projected fields on first item
        NotificationResponse summary = pageA.getContent().get(0);
        assertThat(summary.id()).isEqualTo(a3.getId());
        assertThat(summary.channel()).isEqualTo(Channel.WEBHOOK);
        assertThat(summary.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(summary.recipient()).isEqualTo("https://webhook.example.com");
        assertThat(summary.subject()).isNull();
        assertThat(summary.createdAt()).isNotNull();

        // 2. Channel filter
        Page<NotificationResponse> emailOnly = notificationRepository.findSummariesByTenantIdAndChannel(
                tenantA, Channel.EMAIL, pageReq
        );
        assertThat(emailOnly.getTotalElements()).isEqualTo(2);
        assertThat(emailOnly.getContent()).extracting(NotificationResponse::id)
                .containsExactly(a2.getId(), a1.getId());

        // 3. Status filter
        Page<NotificationResponse> queuedOnly = notificationRepository.findSummariesByTenantIdAndStatus(
                tenantA, NotificationStatus.QUEUED, pageReq
        );
        assertThat(queuedOnly.getTotalElements()).isEqualTo(2);
        assertThat(queuedOnly.getContent()).extracting(NotificationResponse::id)
                .containsExactly(a3.getId(), a1.getId());

        // 4. Combined Channel + Status filter
        Page<NotificationResponse> emailDelivered = notificationRepository.findSummariesByTenantIdAndChannelAndStatus(
                tenantA, Channel.EMAIL, NotificationStatus.DELIVERED, pageReq
        );
        assertThat(emailDelivered.getTotalElements()).isEqualTo(1);
        assertThat(emailDelivered.getContent().get(0).id()).isEqualTo(a2.getId());

        // 5. Tenant B isolation check
        Page<NotificationResponse> pageB = notificationRepository.findSummariesByTenantId(tenantB, pageReq);
        assertThat(pageB.getTotalElements()).isEqualTo(1);
        assertThat(pageB.getContent().get(0).recipient()).isEqualTo("bob@example.com");

        // 6. Prove full body reads remain preserved for detail endpoint
        NotificationEntity detailA1 = notificationRepository.findByIdAndTenantId(a1.getId(), tenantA).orElseThrow();
        assertThat(detailA1.getBody()).isEqualTo("Full message body A1");
    }
}
