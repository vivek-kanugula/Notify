package com.notify.dlq.model;

/**
 * How well a quarantined record could be tied to a notification, decided by the <b>database</b> (/ ).
 *
 * <p>An association answers "what does this record refer to?" and never "what caused the failure?" - a verified
 * association proves neither causation nor that any lifecycle change is warranted.
 */
public enum QuarantineAssociation {

    /** Parsed, the notification exists, and its database tenant matches the payload tenant. */
    RESOLVED,

    /** The record value is not a dispatch envelope this version understands, or carries no usable identifier. */
    UNPARSABLE,

    /** Parsed, but no notification exists with that id. No id is invented. */
    UNKNOWN_NOTIFICATION,

    /** Parsed and the notification exists, but the payload names a different tenant than the database does. */
    TENANT_MISMATCH
}
