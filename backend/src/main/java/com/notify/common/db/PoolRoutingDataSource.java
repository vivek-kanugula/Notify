package com.notify.common.db;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Single {@code @Primary} {@link DataSource} that routes each connection request to the primary pool or,
 * inside a {@link WorkerPoolScope}, to the dedicated worker pool (/ ).
 *
 * <p>JPA, Spring Data, JdbcTemplate, Flyway and Quartz all resolve this one bean, so they share one
 * transaction-bound connection per transaction. Threads without a scope use the primary pool, exactly as
 * before the worker existed. The worker target exists only when the email worker is enabled; entering a
 * worker scope without it fails fast instead of silently falling back to the primary pool.
 */
public class PoolRoutingDataSource extends AbstractRoutingDataSource {

    public PoolRoutingDataSource(DataSource primary, @Nullable DataSource worker) {
        Map<Object, Object> targets = new HashMap<>();
        targets.put(WorkerPoolScope.PoolKey.PRIMARY, primary);
        if (worker != null) {
            targets.put(WorkerPoolScope.PoolKey.WORKER, worker);
        }
        setTargetDataSources(targets);
        setDefaultTargetDataSource(primary);
        setLenientFallback(false);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return WorkerPoolScope.currentKey();
    }
}
