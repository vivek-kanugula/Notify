package com.notify.common.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public enum ApiKeyScope {
    NOTIFICATIONS_SEND("notifications:send"),
    NOTIFICATIONS_READ("notifications:read"),
    NOTIFICATIONS_CANCEL("notifications:cancel"),
    TEMPLATES_READ("templates:read"),
    TEMPLATES_WRITE("templates:write"),
    INAPP_STREAM("inapp:stream"),
    USAGE_READ("usage:read");

    private final String value;

    private static final Map<String, ApiKeyScope> BY_VALUE;

    static {
        Map<String, ApiKeyScope> map = new HashMap<>();
        for (ApiKeyScope scope : values()) {
            map.put(scope.getValue(), scope);
        }
        BY_VALUE = Collections.unmodifiableMap(map);
    }

    ApiKeyScope(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Optional<ApiKeyScope> fromValue(String value) {
        return Optional.ofNullable(BY_VALUE.get(value));
    }
}
