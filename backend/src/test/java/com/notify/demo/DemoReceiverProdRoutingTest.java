package com.notify.demo;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.demo.controller.DemoWebhookReceiverController;
import com.notify.demo.service.DemoWebhookReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F3: under {@code prod} the demo receiver must not exist — as a <em>route</em>, not merely as a disabled feature.
 *
 * <p>The original build registered the controller in every profile and relied on the startup guards, which only
 * refuse the demo URL, the allowlist and the chaos flag. A production deployment therefore still exposed an
 * unauthenticated route that performed database work per request. This test asserts the routing itself, so the
 * regression cannot come back behind a property.
 *
 * <p>{@code notify.email.provider=resend} is required because {@code ProductionProviderGuard} refuses the fake
 * provider under {@code prod}; the placeholder key is never used, since nothing in this test sends anything.
 */
@ActiveProfiles({"test", "prod"})
@TestPropertySource(properties = {
        "notify.jwt.secret=a_very_strong_and_secure_jwt_signing_key_32_chars",
        "notify.admin.default-password=SecureAdminPass123!",
        "spring.datasource.password=RealStrongDbSecret456",
        "notify.email.provider=resend",
        "notify.resend.api-key=placeholder-not-used-in-this-test",
        "notify.worker.email.enabled=false",
        "notify.worker.webhook.enabled=false",
        "notify.worker.inapp.enabled=false"
})
class DemoReceiverProdRoutingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApplicationContext beans;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("under prod the receiver and receipt beans do not exist at all")
    void beansAreAbsentUnderProd() {
        assertThat(beans.getBeansOfType(DemoWebhookReceiverController.class)).isEmpty();
        assertThat(beans.getBeansOfType(DemoWebhookReceiptService.class)).isEmpty();
    }

    @Test
    @DisplayName("under prod the receiver route answers 404: there is no handler, not a disabled one")
    void receiverRouteIsAbsentUnderProd() throws Exception {
        mockMvc.perform(post("/demo/webhook-receiver")
                        .header("X-Notify-Delivery-ID", "00000000-0000-0000-0000-000000000000-1")
                        .header("X-Notify-Signature", "t=1,v1=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("under prod the chaos routes are absent too")
    void chaosRoutesAreAbsentUnderProd() throws Exception {
        mockMvc.perform(post("/demo/webhook-receiver/fail?mode=500")).andExpect(status().isNotFound());
        mockMvc.perform(post("/demo/webhook-receiver/fail?mode=timeout")).andExpect(status().isNotFound());
    }
}
