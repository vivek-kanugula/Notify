package com.notify.delivery.worker.classifier;

import com.notify.delivery.worker.exception.WorkerExceptions;
import com.notify.delivery.worker.exception.WorkerExceptions.DeliveryFailureException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.QueryTimeoutException;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.RetriableException;
import org.hibernate.TransactionException;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

/**
 * The single source of truth for worker failure categories (plan section 8.1). It drives both the error handler's
 * back-off function and the recoverer, so retry selection and recovery cannot disagree.
 *
 * <p>Walks the cause chain from the outermost node inwards; at each node the rules are applied in order and the first
 * match wins. Messages of the classified exceptions are never inspected except where a library gives no other signal.
 */
@Component
public class DeliveryExceptionClassifier {

    private static final int MAX_CAUSE_DEPTH = 32;

    public ErrorCategory classify(Throwable failure) {
        int depth = 0;
        for (Throwable node = failure; node != null && depth < MAX_CAUSE_DEPTH; node = node.getCause(), depth++) {
            ErrorCategory category = classifyNode(node);
            if (category != null) {
                return category;
            }
            if (node.getCause() == node) {
                break;
            }
        }
        return ErrorCategory.UNCLASSIFIED;
    }

    /**
     * Wraps {@code failure} in the typed exception of its category (cause preserved). Already-typed failures are
     * returned unchanged. The message names the category and the root exception class only.
     */
    public DeliveryFailureException normalize(Throwable failure) {
        if (failure instanceof DeliveryFailureException typed) {
            return typed;
        }
        ErrorCategory category = classify(failure);
        String message = category + ": " + failure.getClass().getName();
        return switch (category) {
            case CONNECTIVITY -> new WorkerExceptions.Connectivity(message, failure);
            case CONTENTION -> new WorkerExceptions.Contention(message, failure);
            case ORDERING -> new WorkerExceptions.Ordering(message, failure);
            case BROKER_TRANSIENT -> new WorkerExceptions.BrokerTransient(message, failure);
            case UNRESOLVED -> new WorkerExceptions.Unresolved(message, failure);
            case POISON -> new WorkerExceptions.Poison(message, failure);
            case NON_TRANSIENT -> new WorkerExceptions.NonTransient(message, failure);
            case UNCLASSIFIED -> new WorkerExceptions.Unclassified(message, failure);
        };
    }

    // ─── Rules, applied in order to one node ───────────────────────────────────────────────────────────────────────

    private ErrorCategory classifyNode(Throwable node) {
        // 1. Own typed exceptions
        if (node instanceof DeliveryFailureException typed) {
            return typed.category();
        }
        // 2. Malformed input: only the explicit parse-step exception
        if (node instanceof WorkerExceptions.PoisonMessageException) {
            return ErrorCategory.POISON;
        }
        // 3. Kafka client transient failures (including acknowledgment/commit failures)
        if (node instanceof RetriableException
                || node instanceof CommitFailedException
                || node instanceof RebalanceInProgressException) {
            return ErrorCategory.BROKER_TRANSIENT;
        }
        // 4. Hikari pool timeout: connectivity vs saturation vs authentication
        if (node instanceof SQLTransientConnectionException) {
            if (hasNonTransientSqlState(node)) {
                return ErrorCategory.NON_TRANSIENT;
            }
            return hasConnectivityCause(node) ? ErrorCategory.CONNECTIVITY : ErrorCategory.CONTENTION;
        }
        // 5. Connection loss, authentication, invalid catalog
        if (node instanceof JDBCConnectionException) {
            return ErrorCategory.CONNECTIVITY;
        }
        if (node instanceof SQLException sql) {
            ErrorCategory byState = classifySqlState(sql.getSQLState());
            if (byState != null) {
                return byState;
            }
            // JDBC-standard connection and timeout exception types carry no SQLState we can rely on
            if (sql instanceof SQLNonTransientConnectionException || sql instanceof SQLRecoverableException) {
                return ErrorCategory.CONNECTIVITY;
            }
            if (sql instanceof SQLTimeoutException) {
                return ErrorCategory.CONTENTION;
            }
            // After a connection is marked broken the pool hands out a closed proxy: every later call (including the
            // rollback that replaces the original exception) fails with a bare SQLException("Connection is closed").
            if (isClosedConnectionMessage(sql.getMessage())) {
                return ErrorCategory.CONNECTIVITY;
            }
        }
        if ((node instanceof CannotCreateTransactionException || node instanceof DataAccessResourceFailureException)) {
            if (hasNonTransientSqlState(node)) {
                return ErrorCategory.NON_TRANSIENT;
            }
            if (hasConnectivityCause(node)) {
                return ErrorCategory.CONNECTIVITY;
            }
        }
        // 6. Timeouts and locks. Do not assume every timeout carries an SQLState.
        if (node instanceof org.springframework.dao.QueryTimeoutException
                || node instanceof TransactionTimedOutException
                || node instanceof PessimisticLockingFailureException
                || node instanceof QueryTimeoutException
                || node instanceof LockTimeoutException
                || node instanceof PessimisticLockException
                || isHibernateTransactionTimeout(node)) {
            return ErrorCategory.CONTENTION;
        }
        // 7. Schema / data faults
        if (node instanceof DataIntegrityViolationException || node instanceof InvalidDataAccessResourceUsageException) {
            return ErrorCategory.NON_TRANSIENT;
        }
        return null;
    }

    private static boolean isClosedConnectionMessage(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        // "Connection is not available, request timed out" is the Hikari pool-saturation message: CONTENTION, not this.
        return m.contains("connection is closed") || m.contains("connection has been closed");
    }

    private static boolean isHibernateTransactionTimeout(Throwable node) {
        return node instanceof TransactionException
                && node.getMessage() != null
                && node.getMessage().toLowerCase(Locale.ROOT).contains("timeout");
    }

    /** SQLState to category for a single {@link SQLException}; {@code null} when the state says nothing. */
    private static ErrorCategory classifySqlState(String state) {
        if (state == null || state.length() < 2) {
            return null;
        }
        // Authentication / invalid catalog: configuration faults, not transient
        if (state.startsWith("28") || state.equals("3D000")) {
            return ErrorCategory.NON_TRANSIENT;
        }
        // Lock / deadlock / cancelled statement: contention (checked before the generic class 57 connectivity rule)
        if (state.equals("40P01") || state.equals("40001") || state.equals("55P03") || state.equals("57014")) {
            return ErrorCategory.CONTENTION;
        }
        // Connection exception, admin shutdown / crash shutdown / cannot connect now, insufficient resources
        if (state.startsWith("08") || state.equals("57P01") || state.equals("57P02") || state.equals("57P03")
                || state.startsWith("53")) {
            return ErrorCategory.CONNECTIVITY;
        }
        // Integrity constraint violation, syntax error or access rule violation
        if (state.startsWith("23") || state.startsWith("42")) {
            return ErrorCategory.NON_TRANSIENT;
        }
        return null;
    }

    private static boolean hasConnectivityCause(Throwable start) {
        int depth = 0;
        for (Throwable t = start; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof IOException) {
                return true; // SocketException, ConnectException, timeouts, unknown host, EOF
            }
            if (t instanceof JDBCConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql && (classifySqlState(sql.getSQLState()) == ErrorCategory.CONNECTIVITY
                    || sql instanceof SQLNonTransientConnectionException || sql instanceof SQLRecoverableException
                    || isClosedConnectionMessage(sql.getMessage()))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean hasNonTransientSqlState(Throwable start) {
        int depth = 0;
        for (Throwable t = start; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("28") || state.equals("3D000"))) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
