package com.notify;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A base for suites that assert on a <b>global</b> query and therefore cannot share a database.
 *
 * <p><b>Why this exists, and why the obvious approaches do not work.</b> Nearly every suite here is isolated by
 * tenant, which is enough while every assertion is tenant-scoped. The stuck-work scan is deliberately global, so
 * another suite's rows land inside its results. Two earlier attempts were wrong:
 * <ul>
 *   <li>deleting other tenants' PROCESSING rows <b>by age</b> - unsound, because several recovery suites
 *       deliberately back-date their claims, so an old timestamp says nothing about whether a row belongs to a
 *       finished fixture, and the delete can destroy a running suite's state;</li>
 *   <li>registering a different {@code spring.datasource.url} from a subclass of
 *       {@link AbstractPostgresIntegrationTest} - silently ineffective. All {@code @DynamicPropertySource} methods
 *       write into one property source and the parent's registration runs <b>last</b>, so it overwrites the
 *       subclass's. Measured: the context kept using the shared {@code test} database while the override looked
 *       applied.</li>
 * </ul>
 *
 * <p>So this base does not extend the shared ones at all. It owns a PostgreSQL container and a Kafka container that
 * no suite outside this hierarchy can reach, and Flyway migrates the database from V1 like any other.
 *
 * <p><b>The isolation scope, precisely.</b> These containers are {@code static}, so they are created once per JVM
 * run and <b>shared by every subclass of this class</b> - they are not per-suite. What that buys is separation from
 * the ~40 suites on the shared containers, which is where the interference came from: those suites write
 * notifications constantly and cannot be reasoned about. Within this hierarchy the suites must still not interfere,
 * and that is handled by each test deleting its own tenant afterwards, which cascades to every row it created, so
 * the tables return to empty between tests whatever order they run in. Each subclass asserts that the notifications
 * table starts empty, which is what would catch a subclass that forgot to clean up after itself. Extra containers
 * per suite are deliberately <b>not</b> created: the sharing causes no interference today, and inventing containers
 * to make a looser sentence true would cost a minute of startup for nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("deprecation")
public abstract class AbstractIsolatedDatabaseTest {

    protected static final PostgreSQLContainer<?> isolatedPostgres;
    protected static final KafkaContainer isolatedKafka;

    static {
        isolatedPostgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "max_connections=200");
        isolatedPostgres.start();
        isolatedKafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
        isolatedKafka.start();
    }

    @DynamicPropertySource
    static void isolatedInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", isolatedPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", isolatedPostgres::getUsername);
        registry.add("spring.datasource.password", isolatedPostgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", isolatedKafka::getBootstrapServers);
    }
}
