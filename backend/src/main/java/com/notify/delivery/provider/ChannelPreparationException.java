package com.notify.delivery.provider;

/**
 * An infrastructure failure that happened while a channel adapter was <em>preparing</em> a delivery, before any
 * provider was contacted (/ database-recovery repair).
 *
 * <p><b>Why this type exists.</b> {@code DeliveryOrchestrator} treats an exception escaping
 * {@link DeliveryChannelAdapter#send} as an unknown remote effect and converts it into a retryable provider failure,
 * which consumes one attempt of the notification's delivery budget and can exhaust it. That is right for a provider
 * call whose outcome is genuinely unknown. It is wrong for a database failure during preparation: no request was ever
 * made, nothing about the provider is unknown, and the accepted worker policy () already has the correct answer
 * for a database failure — classify it and let the container pause or stop, keeping the offset.
 *
 * <p><b>What it must and must not wrap.</b> It is thrown only from an explicit preparation seam that surrounds a
 * database access, and the decision is <em>positional</em>: the thrower knows where it is, so nothing has to guess
 * from the shape of a cause. An adapter must never raise it for a transport exception merely because the cause looks
 * like an infrastructure error, and must never raise it once a provider request may have been sent.
 *
 * <p><b>How it is handled.</b> The orchestrator rethrows it unchanged instead of planning an outcome. It is a plain
 * {@code RuntimeException} and deliberately <em>not</em> a
 * {@code com.notify.delivery.worker.exception.WorkerExceptions.DeliveryFailureException}, so
 * {@code DeliveryExceptionClassifier} walks past this wrapper into the real cause and assigns the category that cause
 * deserves — {@code CONNECTIVITY}, {@code CONTENTION}, {@code NON_TRANSIENT} or {@code UNCLASSIFIED} — exactly as it
 * would for the same failure raised anywhere else in the worker. This type adds a boundary; it decides no category.
 */
public final class ChannelPreparationException extends RuntimeException {

    public ChannelPreparationException(String message, Throwable cause) {
        super(message, cause);
    }
}
