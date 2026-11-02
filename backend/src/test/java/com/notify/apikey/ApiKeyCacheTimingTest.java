package com.notify.apikey;

import com.notify.AbstractPostgresIntegrationTest;
import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.apikey.dto.ApiKeyCreatedResponse;
import com.notify.apikey.dto.CreateApiKeyRequest;
import com.notify.apikey.dto.RotateApiKeyRequest;
import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.apikey.service.ApiKeyService;
import com.notify.common.model.ApiKeyStatus;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyCacheTimingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private ApiKeyStatusCacheService cacheService;

    private TenantEntity createTenant(String name) {
        TenantEntity t = new TenantEntity(name, "timing-" + UUID.randomUUID().toString().substring(0, 8));
        t.setStatus(TenantStatus.ACTIVE);
        return tenantRepository.save(t);
    }

    @Test
    @DisplayName("revokeKey defers cache eviction to AFTER transaction commit (not before)")
    void revokeKeyEvictsAfterCommit() {
        TenantEntity tenant = createTenant("RevokeTimingTenant");
        ApiKeyCreatedResponse created = apiKeyService.createKey(
                tenant.getId(),
                new CreateApiKeyRequest("Timing Key", List.of("notifications:send"), null)
        );
        UUID keyId = created.id();
        ApiKeyEntity initialEntity = apiKeyRepository.findById(keyId).orElseThrow();
        String keyHash = initialEntity.getKeyHash();

        AtomicBoolean evictionCalled = new AtomicBoolean(false);
        AtomicReference<ApiKeyStatus> dbStatusDuringEviction = new AtomicReference<>();

        doAnswer(invocation -> {
            evictionCalled.set(true);
            // Query DB from an independent connection to verify the transaction has committed
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT status FROM api_keys WHERE id = ?")) {
                ps.setObject(1, keyId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        dbStatusDuringEviction.set(ApiKeyStatus.valueOf(rs.getString("status")));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return invocation.callRealMethod();
        }).when(cacheService).evictApiKey(eq(keyHash));

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            apiKeyService.revokeKey(tenant.getId(), keyId);
            // Inside the transaction, eviction must NOT have been called yet!
            assertThat(evictionCalled.get())
                    .as("Cache eviction must not run before the transaction commits")
                    .isFalse();
        });

        // After commit, eviction must have executed
        assertThat(evictionCalled.get()).isTrue();
        assertThat(dbStatusDuringEviction.get())
                .as("Independent DB connection must see REVOKED status when cache is evicted")
                .isEqualTo(ApiKeyStatus.REVOKED);

        // Verify idempotent revoke does not trigger duplicate eviction
        evictionCalled.set(false);
        apiKeyService.revokeKey(tenant.getId(), keyId);
        assertThat(evictionCalled.get())
                .as("Idempotent second revoke on already-revoked key must not evict again")
                .isFalse();
    }

    @Test
    @DisplayName("revokeKey does NOT evict cache if transaction rolls back")
    void revokeKeyDoesNotEvictOnRollback() {
        TenantEntity tenant = createTenant("RollbackRevokeTenant");
        ApiKeyCreatedResponse created = apiKeyService.createKey(
                tenant.getId(),
                new CreateApiKeyRequest("Rollback Key", List.of("notifications:send"), null)
        );
        UUID keyId = created.id();
        ApiKeyEntity initialEntity = apiKeyRepository.findById(keyId).orElseThrow();
        String keyHash = initialEntity.getKeyHash();

        AtomicBoolean evictionCalled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            evictionCalled.set(true);
            return invocation.callRealMethod();
        }).when(cacheService).evictApiKey(eq(keyHash));

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        try {
            txTemplate.executeWithoutResult(status -> {
                apiKeyService.revokeKey(tenant.getId(), keyId);
                throw new RuntimeException("Simulated failure to trigger rollback");
            });
        } catch (RuntimeException ignored) {
        }

        // On rollback, cache eviction must never have executed
        assertThat(evictionCalled.get())
                .as("Cache eviction must not execute when transaction rolls back")
                .isFalse();

        // Key in DB must still be ACTIVE
        ApiKeyEntity postRollback = apiKeyRepository.findById(keyId).orElseThrow();
        assertThat(postRollback.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
    }

    @Test
    @DisplayName("rotateKey defers cache eviction of old key to AFTER transaction commit")
    void rotateKeyEvictsAfterCommit() {
        TenantEntity tenant = createTenant("RotateTimingTenant");
        ApiKeyCreatedResponse created = apiKeyService.createKey(
                tenant.getId(),
                new CreateApiKeyRequest("Key To Rotate", List.of("notifications:send"), null)
        );
        UUID oldKeyId = created.id();
        ApiKeyEntity oldEntity = apiKeyRepository.findById(oldKeyId).orElseThrow();
        String oldKeyHash = oldEntity.getKeyHash();

        AtomicBoolean evictionCalled = new AtomicBoolean(false);
        AtomicReference<UUID> rotatedToIdDuringEviction = new AtomicReference<>();

        doAnswer(invocation -> {
            evictionCalled.set(true);
            // Query DB from an independent connection to verify rotated_to_id is committed
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT rotated_to_id FROM api_keys WHERE id = ?")) {
                ps.setObject(1, oldKeyId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rotatedToIdDuringEviction.set((UUID) rs.getObject("rotated_to_id"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return invocation.callRealMethod();
        }).when(cacheService).evictApiKey(eq(oldKeyHash));

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        AtomicReference<ApiKeyCreatedResponse> newKeyRef = new AtomicReference<>();
        txTemplate.executeWithoutResult(status -> {
            ApiKeyCreatedResponse rotated = apiKeyService.rotateKey(
                    tenant.getId(),
                    oldKeyId,
                    new RotateApiKeyRequest(null, null, null, 24)
            );
            newKeyRef.set(rotated);
            // Inside the transaction, eviction must NOT have been called yet!
            assertThat(evictionCalled.get())
                    .as("Cache eviction must not run before rotateKey transaction commits")
                    .isFalse();
        });

        // After commit, eviction must have executed
        assertThat(evictionCalled.get()).isTrue();
        assertThat(rotatedToIdDuringEviction.get())
                .as("Independent DB connection must see committed rotatedToId when cache is evicted")
                .isEqualTo(newKeyRef.get().id());

        // Verify rotation grace was preserved: old key is still ACTIVE in DB
        ApiKeyEntity postRotate = apiKeyRepository.findById(oldKeyId).orElseThrow();
        assertThat(postRotate.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(postRotate.getExpiresAt()).isNotNull();
    }
}
