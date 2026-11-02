package com.notify.dlq.model;

/**
 * Operator workflow state of a quarantined record ().
 *
 * <p>Deliberately smaller than {@link com.notify.common.model.DlqStatus}: a quarantined record cannot be replayed,
 * because replaying it would need a target the record does not reliably name. The only resolution is
 * "reviewed and closed".
 */
public enum QuarantineStatus {
    OPEN,
    DISCARDED
}
