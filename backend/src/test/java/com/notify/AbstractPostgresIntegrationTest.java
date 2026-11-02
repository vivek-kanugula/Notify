package com.notify;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared singleton PostgreSQL 16 Testcontainers base.
 * Starts once per test JVM run and provides dynamic datasource properties to all integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("deprecation")
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> postgres;

    static {
        // Test capacity sizing only (not a connection-leak analysis): every cached Spring context keeps its own connection
        // pool open for the whole JVM run, and the PostgreSQL default of 100 connections was exhausted once enough
        // distinct test contexts existed ("too many clients already").
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "max_connections=500");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
