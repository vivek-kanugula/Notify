package com.notify.common.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Server-selected sender identity for outbound email (/ ).
 * Lives in {@code common.config} so {@code notification} can snapshot it at acceptance without importing {@code delivery}.
 * Client input never influences the sender.
 */
@Validated
@ConfigurationProperties(prefix = "notify.mail")
public record MailProperties(
        @NotBlank @Email String from
) {
}
