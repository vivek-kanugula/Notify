package com.notify.common.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates a bean only when the email delivery worker is enabled
 * ({@code notify.worker.email.enabled=true}) and this instance's role includes workers
 * ({@code notify.role} is {@code all} or {@code worker}). Disabled is the default everywhere ().
 *
 * <p>Lives in {@code common.config} so that {@code common.db} (worker pool) and {@code delivery.worker}
 * can both use it without a package cycle.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnEmailWorkerCondition.class)
public @interface ConditionalOnEmailWorker {
}
