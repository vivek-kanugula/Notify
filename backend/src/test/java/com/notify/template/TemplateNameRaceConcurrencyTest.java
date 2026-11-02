package com.notify.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.AbstractPostgresIntegrationTest;
import com.notify.auth.dto.SignupRequest;
import com.notify.auth.service.JwtService;
import com.notify.common.model.Channel;
import com.notify.common.model.ContentType;
import com.notify.template.dto.CreateTemplateRequest;
import com.notify.template.dto.UpdateTemplateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Regression for the concurrent active-template-name defect: the partial unique index
 * uq_templates_active_name must surface as 422 VALIDATION_FAILED, never 500, when a
 * race slips past the service check-then-insert guard. Covers concurrent create and
 * concurrent active rename.
 */
class TemplateNameRaceConcurrencyTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String createTenantToken(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SignupRequest signup = new SignupRequest(name, name.toLowerCase() + "-" + suffix + "@notify.local", "Password123!");
        MvcResult result = mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signup)))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("accessToken").asText();
    }

    private UUID createTemplate(String token, String name) throws Exception {
        CreateTemplateRequest req = new CreateTemplateRequest(
                name, Channel.IN_APP, "desc", null, "Body {{x}}", ContentType.TEXT);
        MvcResult res = mockMvc.perform(post("/api/v1/templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return UUID.fromString(root.get("id").asText());
    }

    @Test
    @DisplayName("Concurrent create of identical active name: exactly one 201, rest 422, never 500")
    void concurrentCreateSameNameNever500() throws Exception {
        String token = createTenantToken("RaceCreateTenant");
        String name = "Race Template";

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger validationFailed = new AtomicInteger();
        AtomicInteger serverError = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        CreateTemplateRequest req = new CreateTemplateRequest(
                name, Channel.IN_APP, "desc", null, "Body {{x}}", ContentType.TEXT);
        String payload = objectMapper.writeValueAsString(req);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/v1/templates")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn().getResponse().getStatus();
                    if (status == 201) created.incrementAndGet();
                    else if (status == 422) validationFailed.incrementAndGet();
                    else if (status >= 500) serverError.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    serverError.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(serverError.get()).as("no 5xx responses").isZero();
        assertThat(other.get()).as("only 201/422 expected").isZero();
        assertThat(created.get()).as("exactly one create succeeds under the unique index").isEqualTo(1);
        assertThat(validationFailed.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("Concurrent rename of two active templates to same name: one 200, one 422, never 500")
    void concurrentRenameToSameNameNever500() throws Exception {
        String token = createTenantToken("RaceRenameTenant");
        UUID idA = createTemplate(token, "Alpha Template");
        UUID idB = createTemplate(token, "Beta Template");

        String target = "Merged Name";
        String payload = objectMapper.writeValueAsString(new UpdateTemplateRequest(target, "merged"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger validationFailed = new AtomicInteger();
        AtomicInteger serverError = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (UUID id : List.of(idA, idB)) {
            executor.submit(() -> {
                try {
                    start.await();
                    int status = mockMvc.perform(put("/api/v1/templates/" + id)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) ok.incrementAndGet();
                    else if (status == 422) validationFailed.incrementAndGet();
                    else if (status >= 500) serverError.incrementAndGet();
                    else other.incrementAndGet();
                } catch (Exception e) {
                    serverError.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(serverError.get()).as("no 5xx responses").isZero();
        assertThat(other.get()).as("only 200/422 expected").isZero();
        assertThat(ok.get()).as("exactly one rename wins the active name").isEqualTo(1);
        assertThat(validationFailed.get()).isEqualTo(1);
    }
}
