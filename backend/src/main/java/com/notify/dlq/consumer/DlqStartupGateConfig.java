package com.notify.dlq.consumer;

import com.notify.dlq.config.DlqConsumerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Declares the startup gate when at least one dead-letter consumer is enabled (/ , ).
 *
 * <p>With both consumers disabled - the default - no gate, no controller, no listener and no admin client exists.
 */
@Configuration
@ConditionalOnExpression(
        "${notify.dlq.consumer.enabled:false} or ${notify.dlq.dlt-consumer.enabled:false}")
public class DlqStartupGateConfig {

    @Bean
    public DlqConsumerStartupGate dlqConsumerStartupGate(
            DlqConsumerProperties props,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            Environment environment,
            ObjectProvider<DlqConsumerContainerController> controllers
    ) {
        String[] profiles = environment.getActiveProfiles();
        String resolved = profiles.length == 0 ? "default" : String.join(",", profiles);
        return new DlqConsumerStartupGate(props, bootstrapServers, resolved, controllers);
    }
}
