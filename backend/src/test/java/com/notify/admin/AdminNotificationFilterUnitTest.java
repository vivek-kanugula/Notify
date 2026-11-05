package com.notify.admin;

import com.notify.admin.controller.AdminNotificationController;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.service.DeliveryQueryService;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import com.notify.notification.service.NotificationQueryService;
import com.notify.notification.service.NotificationQueryService.NotificationView;
import com.notify.notification.service.NotificationQueryServiceImpl;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminNotificationFilterUnitTest {

    private NotificationQueryService notificationQueryService;
    private DeliveryQueryService deliveryQueryService;
    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken adminAuth;

    @BeforeEach
    void setUp() {
        notificationQueryService = mock(NotificationQueryService.class);
        deliveryQueryService = mock(DeliveryQueryService.class);
        AdminNotificationController controller = new AdminNotificationController(notificationQueryService, deliveryQueryService);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();

        adminAuth = new UsernamePasswordAuthenticationToken(
                Map.of("aud", "notify-admin", "sub", UUID.randomUUID().toString()),
                null
        );
    }

    @Test
    @DisplayName("search passes tenantName filter and returns tenantName in item response")
    void searchWithTenantName() throws Exception {
        UUID notifId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();

        NotificationView view = new NotificationView(
                notifId, tenantId, "Acme Corporation", Channel.EMAIL, NotificationStatus.DELIVERED,
                "user@acme.com", "Test Subject", null, now, null, null,
                1, null, "provider-1", "msg-123", null
        );

        when(notificationQueryService.adminSearch(isNull(), eq("Acme"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .param("tenantName", "Acme")
                        .principal(adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(notifId.toString()))
                .andExpect(jsonPath("$.content[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.content[0].tenantName").value("Acme Corporation"))
                .andExpect(jsonPath("$.content[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.content[0].status").value("DELIVERED"));

        verify(notificationQueryService).adminSearch(isNull(), eq("Acme"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("search passes both tenantId and tenantName filters concurrently")
    void searchWithTenantIdAndTenantName() throws Exception {
        UUID notifId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();

        NotificationView view = new NotificationView(
                notifId, tenantId, "Globex Corp", Channel.WEBHOOK, NotificationStatus.QUEUED,
                "https://webhook.site", null, null, now, null, null,
                0, null, null, null, null
        );

        when(notificationQueryService.adminSearch(eq(tenantId), eq("Globex"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .param("tenantId", tenantId.toString())
                        .param("tenantName", "Globex")
                        .principal(adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.content[0].tenantName").value("Globex Corp"))
                .andExpect(jsonPath("$.content[0].channel").value("WEBHOOK"));

        verify(notificationQueryService).adminSearch(eq(tenantId), eq("Globex"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("NotificationQueryServiceImpl resolves tenantName using tenantRepository")
    void queryServiceResolvesTenantNames() {
        NotificationRepository notifRepo = mock(NotificationRepository.class);
        NotificationStatusHistoryRepository historyRepo = mock(NotificationStatusHistoryRepository.class);
        TenantRepository tenantRepo = mock(TenantRepository.class);

        NotificationQueryServiceImpl service = new NotificationQueryServiceImpl(notifRepo, historyRepo, tenantRepo);

        UUID tenantId = UUID.randomUUID();
        NotificationEntity entity = new NotificationEntity();
        entity.setTenantId(tenantId);
        entity.setChannel(Channel.EMAIL);
        entity.setStatus(NotificationStatus.DELIVERED);
        entity.setRecipient("recipient@test.com");
        entity.setCreatedAt(Instant.now());

        TenantEntity tenantEntity = new TenantEntity("Acme Corp", "acme-corp");
        tenantEntity.setId(tenantId);

        when(notifRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));
        when(tenantRepo.findAllById(List.of(tenantId)))
                .thenReturn(List.of(tenantEntity));

        Page<NotificationView> result = service.adminSearch(null, "Acme", Channel.EMAIL, NotificationStatus.DELIVERED, null, null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        NotificationView view = result.getContent().get(0);
        assertThat(view.tenantId()).isEqualTo(tenantId);
        assertThat(view.tenantName()).isEqualTo("Acme Corp");
        assertThat(view.channel()).isEqualTo(Channel.EMAIL);
    }
}
