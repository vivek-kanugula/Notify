package com.notify.common.db;

import com.notify.common.config.OnAnyWorkerCondition;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * Explicit pool beans behind one {@code @Primary} routing {@link DataSource} (/ ).
 *
 * <p>Declaring {@link DataSource} beans makes Boot's auto-configured pool back off
 * ({@code PooledDataSourceConfiguration} is {@code @ConditionalOnMissingBean(DataSource)}). The primary pool is
 * built from the same {@code spring.datasource.*} / {@code spring.datasource.hikari.*} properties as before, so
 * ingress behavior is unchanged. The worker pool exists only when the email worker is enabled and uses the same
 * URL and credentials with its own bounded settings ({@code notify.worker.datasource.hikari.*}).
 * Both pools are beans, so Spring closes them and Micrometer binds pool metrics for each by name.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceRoutingConfig {

    public static final String PRIMARY_POOL_NAME = "notify-primary";
    public static final String WORKER_POOL_NAME = "notify-worker";

    @Bean(name = "primaryPool")
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource primaryPool(DataSourceProperties properties) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName(StringUtils.hasText(properties.getName()) ? properties.getName() : PRIMARY_POOL_NAME);
        return pool;
    }

    // one worker pool serves every channel worker (WorkerPoolScope is thread-bound, not channel-bound).
    // It exists when at least one worker is enabled, and nothing exists when all workers are disabled (the default).
    @Bean(name = "workerPool")
    @Conditional(OnAnyWorkerCondition.class)
    @ConfigurationProperties(prefix = "notify.worker.datasource.hikari")
    public HikariDataSource workerPool(DataSourceProperties properties) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName(WORKER_POOL_NAME);
        return pool;
    }

    @Bean(name = "dataSource")
    @Primary
    public PoolRoutingDataSource dataSource(
            @Qualifier("primaryPool") DataSource primaryPool,
            @Qualifier("workerPool") ObjectProvider<DataSource> workerPool
    ) {
        return new PoolRoutingDataSource(primaryPool, workerPool.getIfAvailable());
    }
}
