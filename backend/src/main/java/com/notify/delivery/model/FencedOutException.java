package com.notify.delivery.model;

/**
 * Thrown inside the result transaction when the fenced conditional update touched zero rows: the claim was
 * superseded, or this claim's result is already durable. Forces rollback of everything else in the transaction.
 */
public class FencedOutException extends RuntimeException {

    public FencedOutException(String message) {
        super(message);
    }
}
