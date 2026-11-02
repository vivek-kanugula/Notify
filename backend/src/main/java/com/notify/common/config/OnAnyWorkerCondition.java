package com.notify.common.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when at least one channel delivery worker is enabled on this instance. The shared worker database pool
 * () exists under this condition: one pool serves every channel worker, and no pool exists when all workers
 * are disabled, which is the default everywhere.
 */
public class OnAnyWorkerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isAnyEnabled(context.getEnvironment());
    }

    public static boolean isAnyEnabled(Environment environment) {
        return OnEmailWorkerCondition.isEnabled(environment)
                || OnWebhookWorkerCondition.isEnabled(environment)
                || OnInAppWorkerCondition.isEnabled(environment);
    }

    /** Number of enabled workers; the shared pool is sized from it (3 listener threads + 1 health poll each). */
    public static int enabledWorkerCount(Environment environment) {
        int count = 0;
        if (OnEmailWorkerCondition.isEnabled(environment)) {
            count++;
        }
        if (OnWebhookWorkerCondition.isEnabled(environment)) {
            count++;
        }
        if (OnInAppWorkerCondition.isEnabled(environment)) {
            count++;
        }
        return count;
    }
}
