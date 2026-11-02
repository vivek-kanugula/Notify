package com.notify.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** K1 (gate semantics): the worker is off unless explicitly enabled AND the instance role includes workers (). */
class OnEmailWorkerConditionTest {

    @Configuration
    static class Config {
        @Bean
        @ConditionalOnEmailWorker
        String workerMarker() {
            return "worker";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private static MockEnvironment env(String enabled, String role) {
        MockEnvironment environment = new MockEnvironment();
        if (enabled != null) {
            environment.setProperty("notify.worker.email.enabled", enabled);
        }
        if (role != null) {
            environment.setProperty("notify.role", role);
        }
        return environment;
    }

    @Test
    @DisplayName("K1 disabled by default: no property, false, or anything but true")
    void disabledByDefault() {
        assertThat(OnEmailWorkerCondition.isEnabled(env(null, null))).isFalse();
        assertThat(OnEmailWorkerCondition.isEnabled(env("false", "all"))).isFalse();
        assertThat(OnEmailWorkerCondition.isEnabled(env("", "all"))).isFalse();
        runner.run(context -> assertThat(context).doesNotHaveBean("workerMarker"));
        runner.withPropertyValues("notify.worker.email.enabled=false").run(context -> assertThat(context).doesNotHaveBean("workerMarker"));
    }

    @Test
    @DisplayName("K1 enabled with role all or worker (case-insensitive); the default role is all")
    void enabledForWorkerRoles() {
        assertThat(OnEmailWorkerCondition.isEnabled(env("true", null))).isTrue();
        assertThat(OnEmailWorkerCondition.isEnabled(env("true", "all"))).isTrue();
        assertThat(OnEmailWorkerCondition.isEnabled(env("true", "worker"))).isTrue();
        assertThat(OnEmailWorkerCondition.isEnabled(env("true", " WORKER "))).isTrue();
        runner.withPropertyValues("notify.worker.email.enabled=true").run(context -> assertThat(context).hasBean("workerMarker"));
        runner.withPropertyValues("notify.worker.email.enabled=true", "notify.role=worker")
                .run(context -> assertThat(context).hasBean("workerMarker"));
    }

    @Test
    @DisplayName("K1 an API-only instance never runs the worker even when the property is true")
    void apiRoleNeverRunsWorker() {
        assertThat(OnEmailWorkerCondition.isEnabled(env("true", "api"))).isFalse();
        assertThat(OnEmailWorkerCondition.isEnabled(env("true", ""))).isFalse();
        runner.withPropertyValues("notify.worker.email.enabled=true", "notify.role=api")
                .run(context -> assertThat(context).doesNotHaveBean("workerMarker"));
    }
}
