package com.notify.delivery.worker.classifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.notify.delivery.worker.exception.WorkerExceptions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.hibernate.TransactionException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/** C1: classifier table, raw and wrapped exceptions, Hikari variants, unknown. */
class DeliveryExceptionClassifierTest {

    private final DeliveryExceptionClassifier classifier = new DeliveryExceptionClassifier();

    private static SQLException sql(String state) {
        return new SQLException("sql failure", state);
    }

    // ─── typed exceptions and poison ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 own typed exceptions map to their own category, even when wrapped by the container")
    void typedExceptions() {
        assertThat(classifier.classify(new WorkerExceptions.Connectivity("m", null))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new WorkerExceptions.Contention("m", null))).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new WorkerExceptions.Ordering("m", null))).isEqualTo(ErrorCategory.ORDERING);
        assertThat(classifier.classify(new WorkerExceptions.BrokerTransient("m", null))).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(classifier.classify(new WorkerExceptions.Unresolved("m", null))).isEqualTo(ErrorCategory.UNRESOLVED);
        assertThat(classifier.classify(new WorkerExceptions.Poison("m", null))).isEqualTo(ErrorCategory.POISON);
        assertThat(classifier.classify(new WorkerExceptions.NonTransient("m", null))).isEqualTo(ErrorCategory.NON_TRANSIENT);
        assertThat(classifier.classify(new WorkerExceptions.Unclassified("m", null))).isEqualTo(ErrorCategory.UNCLASSIFIED);
        assertThat(classifier.classify(new ListenerExecutionFailedException("wrapped", new WorkerExceptions.Ordering("m", null))))
                .isEqualTo(ErrorCategory.ORDERING);
    }

    @Test
    @DisplayName("C1 only the explicit parse-step exception is POISON; a Jackson exception anywhere else is a defect")
    void poisonIsExplicitOnly() {
        assertThat(classifier.classify(new WorkerExceptions.PoisonMessageException("bad payload", null))).isEqualTo(ErrorCategory.POISON);
        assertThat(classifier.classify(new ListenerExecutionFailedException("w",
                new WorkerExceptions.PoisonMessageException("bad payload", null)))).isEqualTo(ErrorCategory.POISON);
        assertThat(classifier.classify(new JsonProcessingException("jackson failure elsewhere") { }))
                .isEqualTo(ErrorCategory.UNCLASSIFIED);
        assertThat(classifier.classify(new IllegalArgumentException("not poison"))).isEqualTo(ErrorCategory.UNCLASSIFIED);
    }

    @Test
    @DisplayName("C1 the outermost typed exception wins over a deeper raw cause")
    void outermostWins() {
        Throwable failure = new WorkerExceptions.Ordering("ordering", sql("08006"));
        assertThat(classifier.classify(failure)).isEqualTo(ErrorCategory.ORDERING);
    }

    // ─── Kafka ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 Kafka retriable, commit and rebalance failures are BROKER_TRANSIENT")
    void kafkaTransient() {
        assertThat(classifier.classify(new TimeoutException("t"))).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(classifier.classify(new DisconnectException("d"))).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(classifier.classify(new RetriableCommitFailedException("c"))).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(classifier.classify(new CommitFailedException())).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(classifier.classify(new RebalanceInProgressException("r"))).isEqualTo(ErrorCategory.BROKER_TRANSIENT);
        assertThat(classifier.classify(new ListenerExecutionFailedException("w", new CommitFailedException())))
                .isEqualTo(ErrorCategory.BROKER_TRANSIENT);
    }

    // ─── JDBC / SQLState ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 SQLState table: connection loss, contention, auth, integrity/syntax, unknown")
    void sqlStates() {
        for (String state : new String[]{"08000", "08001", "08003", "08006", "57P01", "57P02", "57P03", "53300"}) {
            assertThat(classifier.classify(sql(state))).as(state).isEqualTo(ErrorCategory.CONNECTIVITY);
        }
        for (String state : new String[]{"40P01", "40001", "55P03", "57014"}) {
            assertThat(classifier.classify(sql(state))).as(state).isEqualTo(ErrorCategory.CONTENTION);
        }
        for (String state : new String[]{"28000", "28P01", "3D000", "23505", "23503", "42601", "42P01", "42703"}) {
            assertThat(classifier.classify(sql(state))).as(state).isEqualTo(ErrorCategory.NON_TRANSIENT);
        }
        for (String state : new String[]{"XX000", "P0001", "22012", null}) {
            assertThat(classifier.classify(sql(state))).as(String.valueOf(state)).isEqualTo(ErrorCategory.UNCLASSIFIED);
        }
    }

    @Test
    @DisplayName("C1 wrappers around a connection-loss cause are CONNECTIVITY")
    void wrappedConnectionLoss() {
        assertThat(classifier.classify(new CannotCreateTransactionException("tx", sql("08001")))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new CannotCreateTransactionException("tx", new ConnectException("refused"))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new DataAccessResourceFailureException("res", new EOFException()))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new JpaSystemException(new RuntimeException("jpa", sql("08006"))))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new JDBCConnectionException("hib", sql("08006")))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new RuntimeException("outer", new RuntimeException("mid", new UnknownHostException("db")))))
                .as("IOException below an unknown wrapper is not enough on its own").isEqualTo(ErrorCategory.UNCLASSIFIED);
    }

    @Test
    @DisplayName("C1 a bare SQLException(\"Connection is closed\") from a broken pool connection, also below a rollback-failure wrapper, is CONNECTIVITY")
    void closedConnectionAfterBrokenConnection() {
        assertThat(classifier.classify(new SQLException("Connection is closed"))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new SQLException("This connection has been closed."))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new JpaSystemException(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed")))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new org.springframework.transaction.TransactionSystemException(
                "Could not roll back JPA transaction", new RuntimeException(new SQLException("Connection is closed")))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new CannotCreateTransactionException("tx", new SQLException("Connection is closed"))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        // unrecognized bare SQL failures stay UNCLASSIFIED (stop, never guess)
        assertThat(classifier.classify(new SQLException("something unexpected"))).isEqualTo(ErrorCategory.UNCLASSIFIED);
        assertThat(classifier.classify(new JpaSystemException(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("something unexpected")))))
                .isEqualTo(ErrorCategory.UNCLASSIFIED);
    }

    @Test
    @DisplayName("C1 JDBC-standard connection exception types are CONNECTIVITY and statement timeouts are CONTENTION")
    void jdbcStandardTypes() {
        assertThat(classifier.classify(new java.sql.SQLNonTransientConnectionException("x"))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new java.sql.SQLRecoverableException("x"))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new java.sql.SQLTimeoutException("x"))).isEqualTo(ErrorCategory.CONTENTION);
    }

    @Test
    @DisplayName("C1 resource-failure wrappers without a connectivity cause are not guessed")
    void wrappersWithoutCause() {
        assertThat(classifier.classify(new CannotCreateTransactionException("tx"))).isEqualTo(ErrorCategory.UNCLASSIFIED);
        assertThat(classifier.classify(new DataAccessResourceFailureException("res"))).isEqualTo(ErrorCategory.UNCLASSIFIED);
    }

    // ─── Hikari ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 Hikari pool timeout: saturated pool is CONTENTION, connection causes are CONNECTIVITY, auth is NON_TRANSIENT")
    void hikariVariants() {
        assertThat(classifier.classify(new SQLTransientConnectionException("pool - Connection is not available, request timed out after 3000ms")))
                .isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new CannotCreateTransactionException("tx",
                new SQLTransientConnectionException("pool timeout"))))
                .as("saturated pool wrapped by Spring").isEqualTo(ErrorCategory.CONTENTION);

        assertThat(classifier.classify(new SQLTransientConnectionException("pool timeout", sql("08001")))).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new SQLTransientConnectionException("pool timeout", new ConnectException("refused"))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new SQLTransientConnectionException("pool timeout", new SocketTimeoutException("read timed out"))))
                .isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(classifier.classify(new CannotCreateTransactionException("tx",
                new SQLTransientConnectionException("pool timeout", sql("08006"))))).isEqualTo(ErrorCategory.CONNECTIVITY);

        assertThat(classifier.classify(new SQLTransientConnectionException("pool timeout", sql("28P01")))).isEqualTo(ErrorCategory.NON_TRANSIENT);
        assertThat(classifier.classify(new CannotCreateTransactionException("tx", new SQLTransientConnectionException("pool", sql("3D000")))))
                .isEqualTo(ErrorCategory.NON_TRANSIENT);
    }

    @Test
    @DisplayName("C1 the real Hikari failure shape against a closed port is CONNECTIVITY (raw and wrapped)")
    void realHikariConnectionRefused() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1&loginTimeout=1");
        config.setUsername("u");
        config.setPassword("p");
        config.setConnectionTimeout(1_500);
        config.setInitializationFailTimeout(-1);
        config.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            Throwable failure = null;
            try {
                pool.getConnection();
            } catch (SQLException e) {
                failure = e;
            }
            assertThat(failure).isInstanceOf(SQLTransientConnectionException.class);
            assertThat(classifier.classify(failure)).isEqualTo(ErrorCategory.CONNECTIVITY);
            assertThat(classifier.classify(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction", failure)))
                    .isEqualTo(ErrorCategory.CONNECTIVITY);
        }
    }

    // ─── timeouts, locks, data faults ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 timeouts and locks are CONTENTION, including those without an SQLState")
    void contention() {
        assertThat(classifier.classify(new QueryTimeoutException("q"))).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new TransactionTimedOutException("t"))).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new CannotAcquireLockException("l"))).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new TransactionException("Transaction timeout expired"))).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new jakarta.persistence.QueryTimeoutException())).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new jakarta.persistence.LockTimeoutException())).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new jakarta.persistence.PessimisticLockException())).isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new RuntimeException("wrapper", new TransactionException("Transaction timeout expired"))))
                .isEqualTo(ErrorCategory.CONTENTION);
        assertThat(classifier.classify(new TransactionException("rollback failed"))).as("other Hibernate transaction faults").isEqualTo(ErrorCategory.UNCLASSIFIED);
    }

    @Test
    @DisplayName("C1 integrity and grammar faults are NON_TRANSIENT")
    void nonTransient() {
        assertThat(classifier.classify(new DataIntegrityViolationException("dup"))).isEqualTo(ErrorCategory.NON_TRANSIENT);
        assertThat(classifier.classify(new BadSqlGrammarException("task", "select", sql("42601")))).isEqualTo(ErrorCategory.NON_TRANSIENT);
        assertThat(classifier.classify(new RuntimeException("w", sql("23505")))).isEqualTo(ErrorCategory.NON_TRANSIENT);
    }

    // ─── unknown, cycles, normalize ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 unknown failures and cause cycles are UNCLASSIFIED without looping")
    void unknownAndCycles() {
        assertThat(classifier.classify(new NullPointerException())).isEqualTo(ErrorCategory.UNCLASSIFIED);
        assertThat(classifier.classify(new IllegalStateException("x"))).isEqualTo(ErrorCategory.UNCLASSIFIED);
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(classifier.classify(a)).isEqualTo(ErrorCategory.UNCLASSIFIED);
    }

    @Test
    @DisplayName("C1 normalize wraps a raw failure in the typed exception of its category and preserves the cause")
    void normalizeWraps() {
        SQLException cause = sql("08006");
        WorkerExceptions.DeliveryFailureException normalized = classifier.normalize(cause);
        assertThat(normalized).isInstanceOf(WorkerExceptions.Connectivity.class);
        assertThat(normalized.category()).isEqualTo(ErrorCategory.CONNECTIVITY);
        assertThat(normalized.getCause()).isSameAs(cause);
        assertThat(normalized.getMessage()).contains("CONNECTIVITY").contains("java.sql.SQLException");

        WorkerExceptions.Ordering typed = new WorkerExceptions.Ordering("o", null);
        assertThat(classifier.normalize(typed)).isSameAs(typed);

        assertThat(classifier.normalize(new IllegalStateException("x"))).isInstanceOf(WorkerExceptions.Unclassified.class);
        assertThat(classifier.normalize(new WorkerExceptions.PoisonMessageException("p", null))).isInstanceOf(WorkerExceptions.Poison.class);
        assertThat(classifier.normalize(new QueryTimeoutException("q"))).isInstanceOf(WorkerExceptions.Contention.class);
    }

    @Test
    @DisplayName("C1 a different raw JDBC class of the same category normalizes to the same exception class, so retry budgets are not reset")
    void sameCategorySameClass() {
        Class<?> first = classifier.normalize(sql("08006")).getClass();
        Class<?> second = classifier.normalize(new CannotCreateTransactionException("tx", new ConnectException("refused"))).getClass();
        assertThat(first).isSameAs(second);
        assertThat(classifier.normalize(new QueryTimeoutException("q")).getClass())
                .isNotSameAs(classifier.normalize(sql("08006")).getClass());
    }

    @Test
    @DisplayName("C1 every category declares retries and a recovery action consistent with the policy table")
    void categoryPolicyTable() {
        assertThat(ErrorCategory.CONNECTIVITY.retries()).isFalse();
        assertThat(ErrorCategory.CONNECTIVITY.recoveryAction()).isEqualTo(ErrorCategory.RecoveryAction.PAUSE_UNTIL_HEALTHY);
        for (ErrorCategory c : new ErrorCategory[]{ErrorCategory.CONTENTION, ErrorCategory.ORDERING, ErrorCategory.BROKER_TRANSIENT}) {
            assertThat(c.retries()).as(c.name()).isTrue();
            assertThat(c.recoveryAction()).as(c.name()).isEqualTo(ErrorCategory.RecoveryAction.PAUSE_WITH_BACKOFF);
        }
        assertThat(ErrorCategory.UNRESOLVED.retries()).isTrue();
        assertThat(ErrorCategory.UNRESOLVED.recoveryAction()).isEqualTo(ErrorCategory.RecoveryAction.DEAD_LETTER);
        assertThat(ErrorCategory.POISON.retries()).isFalse();
        assertThat(ErrorCategory.POISON.recoveryAction()).isEqualTo(ErrorCategory.RecoveryAction.DEAD_LETTER);
        assertThat(ErrorCategory.NON_TRANSIENT.retries()).isFalse();
        assertThat(ErrorCategory.NON_TRANSIENT.recoveryAction()).isEqualTo(ErrorCategory.RecoveryAction.STOP);
        assertThat(ErrorCategory.UNCLASSIFIED.retries()).isFalse();
        assertThat(ErrorCategory.UNCLASSIFIED.recoveryAction()).isEqualTo(ErrorCategory.RecoveryAction.STOP);
    }
}
