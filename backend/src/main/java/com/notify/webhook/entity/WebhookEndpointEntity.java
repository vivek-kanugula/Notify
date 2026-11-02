package com.notify.webhook.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "is_demo", nullable = false)
    private boolean isDemo = false;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "signing_secret_enc", nullable = false, length = 512)
    private String signingSecretEnc;

    @Column(name = "status_callbacks", nullable = false)
    private boolean statusCallbacks = false;

    /**
     * Business time, set by the service from the injected {@code Clock} (A3). The field initializer is only a safety
     * net for a row built outside the service; it is never the value a created endpoint carries.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public WebhookEndpointEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isDemo() {
        return isDemo;
    }

    public void setDemo(boolean demo) {
        isDemo = demo;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSigningSecretEnc() {
        return signingSecretEnc;
    }

    public void setSigningSecretEnc(String signingSecretEnc) {
        this.signingSecretEnc = signingSecretEnc;
    }

    public boolean isStatusCallbacks() {
        return statusCallbacks;
    }

    public void setStatusCallbacks(boolean statusCallbacks) {
        this.statusCallbacks = statusCallbacks;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
