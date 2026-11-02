package com.notify.webhook.config;

import com.notify.common.config.OnWebhookWorkerCondition;
import com.notify.demo.config.DemoProperties;
import com.notify.demo.config.DemoWebhookProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Startup guards for the webhook feature (/ ). Each one fails the context instead of letting a weaker path run:
 *
 * <ul>
 *   <li>the webhook worker cannot start without a signing-secret encryption key — it could neither sign nor read a
 *       stored secret, so every delivery would fail at the first attempt;</li>
 *   <li>the {@code prod} profile refuses the demo/local destination allowance, which is the only way the policy
 *       permits plain {@code http} or a loopback address ();</li>
 *   <li>the {@code prod} profile refuses a configured demo webhook URL, which would point every new tenant's default
 *       endpoint at a demo receiver ().</li>
 * </ul>
 *
 * <p>Without a key but with the worker disabled the application starts normally: the configuration API then answers
 * {@code 503 FEATURE_NOT_YET_AVAILABLE}, which is the project's stub policy for a deliberately blocked path.
 */
@Component
public class WebhookStartupGuard {

    public WebhookStartupGuard(
            WebhookProperties webhookProperties,
            DemoProperties demoProperties,
            DemoWebhookProperties demoWebhookProperties,
            Environment environment
    ) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));

        if (OnWebhookWorkerCondition.isEnabled(environment) && !webhookProperties.secretKeyConfigured()) {
            throw new IllegalStateException(
                    "notify.worker.webhook.enabled=true requires notify.webhook.secret-key "
                            + "(env NOTIFY_WEBHOOK_SECRET_KEY, base64 of 32 bytes)");
        }
        if (production && !webhookProperties.demoAllowlist().isEmpty()) {
            throw new IllegalStateException(
                    "notify.webhook.demo-allowlist must be empty with the 'prod' profile; it is the only path that "
                            + "allows plain http or a loopback webhook destination");
        }
        if (production && demoProperties.provisioningEnabled()) {
            throw new IllegalStateException(
                    "notify.demo.webhook-url must not be set with the 'prod' profile; it would point every new "
                            + "tenant's default webhook endpoint at a demo receiver");
        }
        if (production && demoWebhookProperties.chaosEnabled()) {
            throw new IllegalStateException(
                    "notify.demo.receiver.chaos-enabled must not be true with the 'prod' profile; the failure"
                            + "-injection routes are a verification affordance and must not exist in production");
        }
    }
}
