package com.notify.quartz.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

/**
 * Ensures Quartz SchedulerFactoryBean participates in Spring's PlatformTransactionManager.
 * In test profiles with multiple cached ApplicationContexts in the same JVM, gives each
 * context a unique scheduler name so Quartz's static DBConnectionManager does not cross-wire
 * connection providers between contexts.
 */
@Configuration
public class QuartzConfig {

    @Bean
    public SchedulerFactoryBeanCustomizer quartzSchedulerCustomizer(
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            Environment environment
    ) {
        String configuredName = environment.getProperty("spring.quartz.scheduler-name");
        String schedulerName = (configuredName != null && !configuredName.isBlank())
                ? configuredName
                : (environment.matchesProfiles("test")
                    ? "test-sched-" + UUID.randomUUID()
                    : "notify-scheduler");

        return schedulerFactoryBean -> {
            schedulerFactoryBean.setSchedulerName(schedulerName);
            schedulerFactoryBean.setTransactionManager(transactionManager);
        };
    }
}
