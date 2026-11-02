package com.notify.delivery.service;

import com.notify.delivery.worker.classifier.DeliveryExceptionClassifier;
import com.notify.delivery.worker.classifier.ErrorCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Bounded in-memory retry for short database blips between a committed claim and the committed result (plan
 * section 6.5). Up to 4 attempts (initial + 3) with sleeps 50/150/300 ms, only for CONNECTIVITY and CONTENTION
 * failures; every other category is rethrown immediately. The provider is never called again by this helper.
 */
@Component
public class TransientDbRetry {

    private static final long[] DEFAULT_DELAYS_MS = {50, 150, 300};

    private final DeliveryExceptionClassifier classifier;
    private final long[] delaysMs;

    @Autowired
    public TransientDbRetry(DeliveryExceptionClassifier classifier) {
        this(classifier, DEFAULT_DELAYS_MS);
    }

    public TransientDbRetry(DeliveryExceptionClassifier classifier, long[] delaysMs) {
        this.classifier = classifier;
        this.delaysMs = delaysMs.clone();
    }

    public <T> T call(Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= delaysMs.length; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                last = e;
                if (attempt < delaysMs.length) {
                    sleep(delaysMs[attempt]);
                }
            }
        }
        throw last;
    }

    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    private boolean isRetryable(RuntimeException e) {
        ErrorCategory category = classifier.classify(e);
        return category == ErrorCategory.CONNECTIVITY || category == ErrorCategory.CONTENTION;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a database operation", ie);
        }
    }
}
