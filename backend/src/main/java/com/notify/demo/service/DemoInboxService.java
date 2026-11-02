package com.notify.demo.service;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.demo.config.DemoProperties;
import com.notify.demo.dto.DemoInboxItemResponse;
import com.notify.notification.service.NotificationQueryService;
import com.notify.notification.service.NotificationQueryService.NotificationView;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the public demo inbox (/ , ). Returns a 404 when disabled, otherwise the newest 50
 * notifications of the configured demo tenants with masked recipients and sanitized subjects.
 */
@Service
public class DemoInboxService {

    private static final int MAX_ITEMS = 50;
    private static final int MAX_SUBJECT = 64;
    private static final Pattern DISALLOWED_SUBJECT = Pattern.compile("[^A-Za-z0-9 .,!?:;'()\\-]");

    private final DemoProperties demoProperties;
    private final TenantRepository tenantRepository;
    private final NotificationQueryService notificationQueryService;

    public DemoInboxService(DemoProperties demoProperties,
                            TenantRepository tenantRepository,
                            NotificationQueryService notificationQueryService) {
        this.demoProperties = demoProperties;
        this.tenantRepository = tenantRepository;
        this.notificationQueryService = notificationQueryService;
    }

    @Transactional(readOnly = true)
    public List<DemoInboxItemResponse> inbox() {
        if (!demoProperties.inboxEnabled()) {
            throw new NotifyException(ErrorCode.NOT_FOUND, "Demo inbox is not available");
        }
        List<TenantEntity> demoTenants = tenantRepository.findBySlugIn(demoProperties.tenantSlugs());
        if (demoTenants.isEmpty()) {
            return List.of();
        }

        Map<UUID, TenantEntity> tenantMap = demoTenants.stream()
                .collect(Collectors.toMap(TenantEntity::getId, Function.identity(), (a, b) -> a));
        List<UUID> tenantIds = List.copyOf(tenantMap.keySet());

        return notificationQueryService.recentForTenants(tenantIds, MAX_ITEMS).stream()
                .map(v -> toItem(v, tenantMap.get(v.tenantId())))
                .toList();
    }

    public DemoInboxItemResponse toItem(NotificationView v, TenantEntity tenant) {
        String slug = tenant != null ? tenant.getSlug() : null;
        String name = tenant != null ? tenant.getName() : null;
        return new DemoInboxItemResponse(
                v.id(), slug, name, v.channel(), v.status(),
                maskRecipient(v.recipient()), sanitizeSubject(v.subject()),
                v.createdAt());
    }

    public static String maskRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "***";
        }
        int at = recipient.indexOf('@');
        if (at <= 0) {
            // webhook url, recipient ref, or phone number
            if (recipient.length() <= 4) {
                return "***";
            }
            return recipient.charAt(0) + "***" + recipient.substring(recipient.length() - 3);
        }
        char first = recipient.charAt(0);
        return first + "***" + recipient.substring(at);
    }

    public static String sanitizeSubject(String subject) {
        if (subject == null) {
            return null;
        }
        String cleaned = DISALLOWED_SUBJECT.matcher(subject).replaceAll("").trim();
        if (cleaned.length() > MAX_SUBJECT) {
            cleaned = cleaned.substring(0, MAX_SUBJECT);
        }
        return cleaned;
    }
}
