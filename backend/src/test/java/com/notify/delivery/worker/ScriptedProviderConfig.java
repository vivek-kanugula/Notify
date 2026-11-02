package com.notify.delivery.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Makes {@link ScriptedEmailProvider} the primary {@code EmailProvider} (the fake provider bean still exists). */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedProviderConfig {

    @Bean
    @Primary
    public ScriptedEmailProvider scriptedEmailProvider() {
        return new ScriptedEmailProvider();
    }
}
