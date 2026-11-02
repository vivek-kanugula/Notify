package com.notify.delivery.worker.exception;

import com.notify.delivery.worker.classifier.ErrorCategory;

/**
 * Typed exceptions of the delivery worker. The listener normalizes everything it throws into one of the
 * {@link DeliveryFailureException} subtypes, so the exception <em>class</em> changes only when the failure
 * <em>category</em> changes (the error handler resets its retry budget on a class change).
 */
public final class WorkerExceptions {

    private WorkerExceptions() {
    }

    /** Base of the typed worker failures; carries its {@link ErrorCategory}. */
    public abstract static class DeliveryFailureException extends RuntimeException {
        private final ErrorCategory category;

        protected DeliveryFailureException(ErrorCategory category, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
        }

        public ErrorCategory category() {
            return category;
        }
    }

    public static final class Connectivity extends DeliveryFailureException {
        public Connectivity(String message, Throwable cause) {
            super(ErrorCategory.CONNECTIVITY, message, cause);
        }
    }

    public static final class Contention extends DeliveryFailureException {
        public Contention(String message, Throwable cause) {
            super(ErrorCategory.CONTENTION, message, cause);
        }
    }

    public static final class Ordering extends DeliveryFailureException {
        public Ordering(String message, Throwable cause) {
            super(ErrorCategory.ORDERING, message, cause);
        }
    }

    public static final class BrokerTransient extends DeliveryFailureException {
        public BrokerTransient(String message, Throwable cause) {
            super(ErrorCategory.BROKER_TRANSIENT, message, cause);
        }
    }

    public static final class Unresolved extends DeliveryFailureException {
        public Unresolved(String message, Throwable cause) {
            super(ErrorCategory.UNRESOLVED, message, cause);
        }
    }

    public static final class Poison extends DeliveryFailureException {
        public Poison(String message, Throwable cause) {
            super(ErrorCategory.POISON, message, cause);
        }
    }

    public static final class NonTransient extends DeliveryFailureException {
        public NonTransient(String message, Throwable cause) {
            super(ErrorCategory.NON_TRANSIENT, message, cause);
        }
    }

    public static final class Unclassified extends DeliveryFailureException {
        public Unclassified(String message, Throwable cause) {
            super(ErrorCategory.UNCLASSIFIED, message, cause);
        }
    }

    /**
     * Thrown only by the payload parse step for a malformed or contract-violating dispatch record. A Jackson or other
     * exception raised anywhere else is a defect and is never classified POISON.
     */
    public static final class PoisonMessageException extends RuntimeException {
        public PoisonMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown by the recoverer when it deliberately does not recover (pause or stop): the error handler then keeps the
     * offset and re-seeks the record instead of committing it.
     */
    public static final class RecoveryRefusedException extends RuntimeException {
        private final ErrorCategory category;

        public RecoveryRefusedException(ErrorCategory category) {
            super("Recovery refused (offset retained): " + category);
            this.category = category;
        }

        public ErrorCategory category() {
            return category;
        }
    }
}
