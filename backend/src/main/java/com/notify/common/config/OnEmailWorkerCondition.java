package com.notify.common.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Condition behind {@link ConditionalOnEmailWorker}. */
public class OnEmailWorkerCondition implements Condition {

    public static final String ENABLED_PROPERTY = "notify.worker.email.enabled";
    public static final String ROLE_PROPERTY = "notify.role";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isEnabled(context.getEnvironment());
    }

    public static boolean isEnabled(Environment environment) {
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
        if (!enabled) {
            return false;
        }
        String role = environment.getProperty(ROLE_PROPERTY, "all").trim().toLowerCase(java.util.Locale.ROOT);
        return role.equals("all") || role.equals("worker");
    }
}
