package com.notify.common.db;

import com.notify.AbstractPostgresIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P6 (disabled half): default test profile, worker disabled. Only the primary pool exists. */
class PoolRoutingWorkerDisabledTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Test
    @DisplayName("P6 worker disabled: no worker pool bean, routing resolves only the primary pool, JdbcTemplate/JPA use the routing bean")
    void onlyPrimaryPoolExists() {
        assertThat(context.containsBean("workerPool")).isFalse();
        assertThat(context.getBeansOfType(HikariDataSource.class)).containsOnlyKeys("primaryPool");
        assertThat(dataSource).isInstanceOf(PoolRoutingDataSource.class);
        assertThat(((PoolRoutingDataSource) dataSource).getResolvedDataSources())
                .containsOnlyKeys(WorkerPoolScope.PoolKey.PRIMARY);
        assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSource);
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("K1 worker disabled by default: no worker, controller, health indicator, listener infrastructure, consumer factory or DLT template")
    void noWorkerBeansByDefault() {
        assertThat(context.getBeansOfType(com.notify.delivery.worker.EmailDeliveryWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(com.notify.delivery.worker.control.WorkerContainerController.class)).isEmpty();
        assertThat(context.getBeansOfType(com.notify.delivery.worker.control.EmailWorkerHealthIndicator.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.kafka.core.ConsumerFactory.class)).isEmpty();
        assertThat(context.containsBean("deliveryDltKafkaTemplate")).isFalse();
        assertThat(context.containsBean("emailKafkaListenerContainerFactory")).isFalse();
        assertThat(context.containsBean("emailWorkerErrorHandler")).isFalse();
        assertThat(context.getBean(com.notify.delivery.worker.config.EmailWorkerProperties.class).enabled()).isFalse();
        assertThat(context.getEnvironment().getProperty("notify.worker.email.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("P6 worker disabled: entering a worker scope fails fast on first connection instead of falling back to the primary pool")
    void workerScopeWithoutWorkerPoolFailsFast() {
        try (WorkerPoolScope ignored = WorkerPoolScope.enter()) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .hasStackTraceContaining("Cannot determine target DataSource");
        }
        assertThat(WorkerPoolScope.isActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("P6 worker disabled: established liveness/readiness/health endpoints stay UP")
    void healthStaysUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        String body = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(json.at("/components/db/status").asText()).as(body).isEqualTo("UP");
        assertThat(body).as("no worker pool is reported when the worker is disabled").doesNotContain("WORKER");
    }
}
