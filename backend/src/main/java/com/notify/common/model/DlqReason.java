package com.notify.common.model;

public enum DlqReason {
    MAX_RETRIES_EXCEEDED,
    PERMANENT_FAILURE,
    TENANT_NOT_ACTIVE,
    POISON_MESSAGE
}
