package com.notify.admin.service;

import com.notify.admin.dto.AdminSystemStatusResponse;
import com.notify.admin.dto.AdminSystemStatusResponse.BreakerStatus;
import com.notify.admin.dto.AdminSystemStatusResponse.ComponentHealth;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.kafka.clients.admin.AdminClient;
import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Probes live component health (DB, Redis, Kafka, Quartz, circuit breakers) for the admin status endpoint
 * (/ ). Every probe is best-effort and fail-soft: an unreachable component reports DOWN/UNKNOWN and
 * never throws, so the endpoint stays available even when infrastructure is degraded.
 */
@Service
public class AdminSystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(AdminSystemStatusService.class);
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";
    private static final String UNKNOWN = "UNKNOWN";

    private final DataSource dataSource;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactory;
    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    private final ObjectProvider<Scheduler> scheduler;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final Clock clock;

    public AdminSystemStatusService(DataSource dataSource,
                                    ObjectProvider<RedisConnectionFactory> redisConnectionFactory,
                                    ObjectProvider<KafkaAdmin> kafkaAdmin,
                                    ObjectProvider<Scheduler> scheduler,
                                    Map<String, CircuitBreaker> circuitBreakers,
                                    Clock clock) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.kafkaAdmin = kafkaAdmin;
        this.scheduler = scheduler;
        this.circuitBreakers = circuitBreakers;
        this.clock = clock;
    }

    public AdminSystemStatusResponse status() {
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        ComponentHealth db = probeDatabase();
        components.put("database", db);
        components.put("redis", probeRedis());
        components.put("kafka", probeKafka());
        components.put("quartz", probeQuartz());

        String overall = UP.equals(db.status()) ? UP : DOWN;
        Map<String, String> grafanaLinks = new LinkedHashMap<>();
        grafanaLinks.put("overview", "/grafana/d/notify-overview/notify-overview");
        grafanaLinks.put("logs", "/grafana/explore?schemaVersion=1&panes=" + java.net.URLEncoder.encode("{\"A\":{\"datasource\":\"notify-loki\",\"queries\":[{\"refId\":\"A\",\"expr\":\"{service_name=~\\\".+\\\"}\",\"queryType\":\"range\"}],\"range\":{\"from\":\"now-1h\",\"to\":\"now\"}}}", java.nio.charset.StandardCharsets.UTF_8));
        grafanaLinks.put("traces", "/grafana/explore?schemaVersion=1&panes=" + java.net.URLEncoder.encode("{\"A\":{\"datasource\":\"notify-tempo\",\"queries\":[{\"refId\":\"A\",\"queryType\":\"traceql\",\"query\":\"{}\"}],\"range\":{\"from\":\"now-1h\",\"to\":\"now\"}}}", java.nio.charset.StandardCharsets.UTF_8));

        return new AdminSystemStatusResponse(overall, clock.instant(), components, breakers(), grafanaLinks);
    }

    private ComponentHealth probeDatabase() {
        Map<String, Object> details = new LinkedHashMap<>();
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("SELECT 1");
            if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                details.put("activeConnections", hikari.getHikariPoolMXBean().getActiveConnections());
            }
            return new ComponentHealth(UP, details);
        } catch (Exception e) {
            log.warn("System status: database probe failed: {}", e.getMessage());
            details.put("error", e.getClass().getSimpleName());
            return new ComponentHealth(DOWN, details);
        }
    }

    private ComponentHealth probeRedis() {
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            return new ComponentHealth(UNKNOWN, Map.of("detail", "not configured"));
        }
        try (var conn = factory.getConnection()) {
            String pong = conn.ping();
            return new ComponentHealth(UP, Map.of("ping", pong == null ? "PONG" : pong));
        } catch (Exception e) {
            log.warn("System status: redis probe failed: {}", e.getMessage());
            return new ComponentHealth(DOWN, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private ComponentHealth probeKafka() {
        KafkaAdmin admin = kafkaAdmin.getIfAvailable();
        if (admin == null) {
            return new ComponentHealth(UNKNOWN, Map.of("detail", "not configured"));
        }
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            var cluster = client.describeCluster();
            int brokers = cluster.nodes().get(2, TimeUnit.SECONDS).size();
            int topics = client.listTopics().names().get(2, TimeUnit.SECONDS).size();
            return new ComponentHealth(UP, Map.of("brokers", brokers, "topicCount", topics));
        } catch (Exception e) {
            log.warn("System status: kafka probe failed: {}", e.getMessage());
            return new ComponentHealth(DOWN, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private ComponentHealth probeQuartz() {
        Scheduler s = scheduler.getIfAvailable();
        if (s == null) {
            return new ComponentHealth(UNKNOWN, Map.of("detail", "not configured"));
        }
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schedulerName", s.getSchedulerName());
            details.put("started", s.isStarted() && !s.isInStandbyMode());
            return new ComponentHealth(s.isStarted() ? UP : DOWN, details);
        } catch (Exception e) {
            log.warn("System status: quartz probe failed: {}", e.getMessage());
            return new ComponentHealth(DOWN, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private List<BreakerStatus> breakers() {
        return circuitBreakers.entrySet().stream()
                .map(e -> new BreakerStatus(
                        e.getValue().getName() != null ? e.getValue().getName() : e.getKey(),
                        e.getValue().getState().name(),
                        e.getValue().getMetrics().getFailureRate()))
                .toList();
    }
}
