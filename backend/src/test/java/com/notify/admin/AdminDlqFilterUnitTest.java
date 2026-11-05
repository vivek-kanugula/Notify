package com.notify.admin;

import com.notify.admin.controller.AdminDlqController;
import com.notify.auth.service.AdminAuthService;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.dlq.config.DlqConsumerProperties;
import com.notify.dlq.dto.DlqEntrySummary;
import com.notify.dlq.dto.DlqPage;
import com.notify.dlq.entity.DeadLetterEntryEntity;
import com.notify.dlq.repository.DeadLetterEntryRepository;
import com.notify.dlq.service.AdminDlqService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDlqFilterUnitTest {

    private AdminDlqService dlqService;
    private AdminAuthService adminAuthService;
    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken adminAuth;

    @BeforeEach
    void setUp() {
        dlqService = mock(AdminDlqService.class);
        adminAuthService = mock(AdminAuthService.class);
        when(dlqService.maxLimit()).thenReturn(100);

        AdminDlqController controller = new AdminDlqController(dlqService, adminAuthService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        adminAuth = new UsernamePasswordAuthenticationToken(
                Map.of("aud", "notify-admin", "sub", UUID.randomUUID().toString()),
                null
        );
    }

    @Test
    @DisplayName("DLQ list endpoint passes tenantName filter and returns tenantName in item summary")
    void listWithTenantName() throws Exception {
        UUID entryId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();

        DlqEntrySummary summary = new DlqEntrySummary(
                entryId, notifId, tenantId, "acme-corp", "Acme Corporation",
                Channel.EMAIL, DlqReason.MAX_RETRIES_EXCEEDED, "TIMEOUT", 3,
                DlqStatus.OPEN, 0, now, now
        );

        when(dlqService.list(isNull(), isNull(), isNull(), isNull(), eq("Acme"), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new DlqPage<>(List.of(summary), null));

        mockMvc.perform(get("/api/v1/admin/dlq")
                        .param("tenantName", "Acme")
                        .principal(adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].entryId").value(entryId.toString()))
                .andExpect(jsonPath("$.items[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.items[0].tenantSlug").value("acme-corp"))
                .andExpect(jsonPath("$.items[0].tenantName").value("Acme Corporation"))
                .andExpect(jsonPath("$.items[0].reason").value("MAX_RETRIES_EXCEEDED"));

        verify(dlqService).list(isNull(), isNull(), isNull(), isNull(), eq("Acme"), isNull(), isNull(), isNull(), anyInt());
    }

    @Test
    @DisplayName("DLQ list endpoint passes both tenantId and tenantName filters concurrently")
    void listWithTenantIdAndTenantName() throws Exception {
        UUID entryId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();

        DlqEntrySummary summary = new DlqEntrySummary(
                entryId, notifId, tenantId, "globex", "Globex",
                Channel.WEBHOOK, DlqReason.PERMANENT_FAILURE, "HTTP_500", 5,
                DlqStatus.OPEN, 0, now, now
        );

        when(dlqService.list(isNull(), isNull(), isNull(), eq(tenantId), eq("Globex"), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new DlqPage<>(List.of(summary), null));

        mockMvc.perform(get("/api/v1/admin/dlq")
                        .param("tenantId", tenantId.toString())
                        .param("tenantName", "Globex")
                        .principal(adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.items[0].tenantName").value("Globex"));

        verify(dlqService).list(isNull(), isNull(), isNull(), eq(tenantId), eq("Globex"), isNull(), isNull(), isNull(), anyInt());
    }

    @Test
    @DisplayName("AdminDlqService.list passes tenantName to repository and maps tenantName in summary")
    void dlqServiceMapsTenantName() {
        DeadLetterEntryRepository entryRepo = mock(DeadLetterEntryRepository.class);
        TenantRepository tenantRepo = mock(TenantRepository.class);
        DlqConsumerProperties props = mock(DlqConsumerProperties.class);
        DlqConsumerProperties.Api api = mock(DlqConsumerProperties.Api.class);
        when(props.api()).thenReturn(api);
        when(api.defaultLimit()).thenReturn(25);
        when(api.maxLimit()).thenReturn(100);

        AdminDlqService service = new AdminDlqService(
                entryRepo, null, null, null, null, tenantRepo, null, props, Clock.systemUTC()
        );

        UUID entryId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();

        DeadLetterEntryEntity entity = new DeadLetterEntryEntity();
        entity.setId(entryId);
        entity.setNotificationId(notifId);
        entity.setTenantId(tenantId);
        entity.setChannel(Channel.EMAIL);
        entity.setReason(DlqReason.MAX_RETRIES_EXCEEDED);
        entity.setAttemptCount(3);
        entity.setStatus(DlqStatus.OPEN);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        TenantEntity tenantEntity = new TenantEntity("Acme Corporation", "acme-corp");
        tenantEntity.setId(tenantId);

        when(entryRepo.searchEntries(isNull(), isNull(), isNull(), eq(tenantId), eq("Acme"), isNull(), isNull(), isNull(), isNull(), eq(26)))
                .thenReturn(List.of(entity));
        when(tenantRepo.findById(tenantId)).thenReturn(Optional.of(tenantEntity));

        DlqPage<DlqEntrySummary> page = service.list(null, null, null, tenantId, "Acme", null, null, null, 25);

        assertThat(page.items()).hasSize(1);
        DlqEntrySummary summary = page.items().get(0);
        assertThat(summary.tenantId()).isEqualTo(tenantId);
        assertThat(summary.tenantSlug()).isEqualTo("acme-corp");
        assertThat(summary.tenantName()).isEqualTo("Acme Corporation");
        assertThat(summary.reason()).isEqualTo(DlqReason.MAX_RETRIES_EXCEEDED);
    }
}
