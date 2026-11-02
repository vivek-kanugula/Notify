package com.notify.demo;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "notify.demo.public=true",
        "notify.demo.tenant-slugs=demo-n20-inbox"
})
class DemoInboxControllerTest extends AbstractPostgresIntegrationTest {

    private static final String DEMO_SLUG = "demo-n20-inbox";

    @Autowired private WebApplicationContext context;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TenantRepository tenantRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private UUID demoTenant() {
        return tenantRepository.findBySlug(DEMO_SLUG)
                .orElseGet(() -> tenantRepository.save(new TenantEntity("Demo Org", DEMO_SLUG))).getId();
    }

    private void persist(UUID tenantId, String recipient, String subject) {
        NotificationEntity n = new NotificationEntity();
        n.setTenantId(tenantId);
        n.setChannel(Channel.EMAIL);
        n.setStatus(NotificationStatus.DELIVERED);
        n.setRecipient(recipient);
        n.setSubject(subject);
        n.setBody("secret body must never appear in the demo feed");
        n.setMetadata("{\"secret\":\"nope\"}");
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        n.setCompletedAt(Instant.now());
        notificationRepository.save(n);
    }

    @Test
    @DisplayName("enabled demo feed returns masked, sanitized items without body")
    void inboxEnabled() throws Exception {
        UUID tenantId = demoTenant();
        persist(tenantId, "john@acme.com", "Order <script>#5</script>!!!");
        persist(tenantId, "jane@acme.com", "Receipt");

        mockMvc.perform(get("/api/v1/demo/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].recipient").value(org.hamcrest.Matchers.matchesPattern("[a-z]\\*\\*\\*@acme\\.com")))
                .andExpect(jsonPath("$[*].subject", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<")))))
                .andExpect(jsonPath("$[0].body").doesNotExist());
    }
}
