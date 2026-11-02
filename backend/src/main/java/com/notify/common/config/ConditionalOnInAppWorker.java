package com.notify.common.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates a bean only when the in-app delivery worker is enabled ({@code notify.worker.inapp.enabled=true}) and
 * this instance's role includes workers ({@code notify.role} is {@code all} or {@code worker}). Disabled is the
 * default everywhere (, extended to every channel worker by and ).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnInAppWorkerCondition.class)
public @interface ConditionalOnInAppWorker {
}
