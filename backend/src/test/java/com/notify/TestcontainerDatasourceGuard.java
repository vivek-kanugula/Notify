package com.notify;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Refuses a test context whose resolved datasource is not this suite's Testcontainer.
 *
 * <p><b>Why this exists, and a correction.</b> Earlier reports described an "existing early datasource guard" on
 * the shared bases. That attribution was wrong: {@link AbstractPostgresIntegrationTest} and
 * {@link AbstractIsolatedDatabaseTest} only <em>register</em> container properties - the only guard in the
 * repository was local to {@code AutomaticRestartRecoveryIntegrationTest}, which checks the environment of the
 * second application context it starts itself. Suites that run **migrations** need the check too, because a
 * misresolved {@code spring.datasource.url} would let Flyway migrate whatever database the property actually
 * points at.
 *
 * <p>It is an {@link ApplicationContextInitializer}, so it runs while the context is being prepared - <b>before
 * any bean is created, and therefore before Flyway runs</b>. A mismatch throws there, so no migration, no schema
 * change and no write can reach the wrong database.
 */
public class TestcontainerDatasourceGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String resolved = context.getEnvironment().getProperty("spring.datasource.url");
        String expected = AbstractPostgresIntegrationTest.postgres.getJdbcUrl();
        if (resolved == null || !resolved.equals(expected)) {
            throw new IllegalStateException("Refusing to start this test context: it resolved datasource "
                    + resolved + " instead of this suite's Testcontainer " + expected
                    + ". Flyway has not run and nothing was written.");
        }
    }
}
