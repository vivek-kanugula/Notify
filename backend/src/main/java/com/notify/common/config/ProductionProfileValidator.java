package com.notify.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * Production profile security guard (/ F38 / F02).
 * Fails fast at startup if the "prod" profile is active and insecure defaults or configurations are detected:
 * 1. Placeholder or weak JWT secret.
 * 2. Placeholder or weak admin default password.
 * 3. Placeholder or blank datasource password.
 * 4. Fake email provider when email worker is enabled.
 * 5. Demo mode enabled (notify.demo.public == true).
 * 6. Webhook demo-allowlist populated (loopbacks forbidden in prod).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionProfileValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionProfileValidator.class);

    public static final String PLACEHOLDER_JWT_SECRET = "placeholder_hs256_secret_key_minimum_32_characters_long";
    public static final String PLACEHOLDER_ADMIN_PASSWORD = "placeholder_admin_password_min_10_chars";
    public static final String PLACEHOLDER_DB_PASSWORD = "placeholder_db_password";

    private final Environment environment;

    public ProductionProfileValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate(this.environment);
    }

    public void validate(Environment env) {
        if (!isProdProfileActive(env)) {
            return;
        }

        log.info("Production profile is active. Validating production security guards...");

        // 1. JWT Secret
        String jwtSecret = env.getProperty("notify.jwt.secret");
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.equals(PLACEHOLDER_JWT_SECRET) || jwtSecret.length() < 32) {
            throw new IllegalStateException("Production security check failed: 'notify.jwt.secret' must not be the placeholder secret and must be at least 32 characters long.");
        }

        // 2. Admin Password
        String adminPassword = env.getProperty("notify.admin.default-password");
        if (adminPassword == null || adminPassword.isBlank() || adminPassword.equals(PLACEHOLDER_ADMIN_PASSWORD) || adminPassword.length() < 10) {
            throw new IllegalStateException("Production security check failed: 'notify.admin.default-password' must not be the placeholder password and must be at least 10 characters long.");
        }

        // 3. Datasource Password
        String dbPassword = env.getProperty("spring.datasource.password");
        if (dbPassword == null || dbPassword.isBlank() || dbPassword.equals(PLACEHOLDER_DB_PASSWORD)) {
            throw new IllegalStateException("Production security check failed: 'spring.datasource.password' must not be the placeholder password and must be non-blank.");
        }

        // 4. Email Provider Check (aligned with ProductionProviderGuard)
        String emailProvider = env.getProperty("notify.email.provider", "fake");
        if ("fake".equalsIgnoreCase(emailProvider.trim())) {
            throw new IllegalStateException("Production security check failed: 'notify.email.provider' cannot be 'fake' in production.");
        }

        // 5. Demo Mode Check
        boolean demoPublic = Boolean.parseBoolean(env.getProperty("notify.demo.public", "false"));
        if (demoPublic) {
            throw new IllegalStateException("Production security check failed: 'notify.demo.public' must be false in production.");
        }

        // 6. Webhook Demo Allowlist Check
        String demoAllowlist = env.getProperty("notify.webhook.demo-allowlist");
        if (demoAllowlist != null && !demoAllowlist.trim().isEmpty()) {
            throw new IllegalStateException("Production security check failed: 'notify.webhook.demo-allowlist' must be empty in production to prevent loopback destinations.");
        }

        log.info("Production security guards validated successfully.");
    }

    public static boolean isProdProfileActive(Environment env) {
        if (env == null || env.getActiveProfiles() == null) {
            return false;
        }
        return Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
    }
}

