package com.notify.idempotency.util;

import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Channel;
import com.notify.notification.dto.CreateNotificationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalHashCalculatorTest {

    @Test
    @DisplayName("Fix verification: Subject 'a:b', body 'c' and subject 'a', body 'b:c' produce different hashes")
    void distinctSubjectAndBodyPairs_produceDifferentHashes() {
        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "a:b",
                "c",
                null, null, null, null, null, null
        );

        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "a",
                "b:c",
                null, null, null, null, null, null
        );

        String hash1 = CanonicalHashCalculator.computeHash(req1);
        String hash2 = CanonicalHashCalculator.computeHash(req2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Equivalent nested map key ordering produces the same hash")
    void equivalentNestedMapKeyOrdering_producesSameHash() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("zebra", "last");
        map1.put("apple", "first");
        map1.put("nested", Map.of("z", 10, "a", 20));

        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("apple", "first");
        map2.put("nested", Map.of("a", 20, "z", 10));
        map2.put("zebra", "last");

        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, map1, null, null
        );

        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, map2, null, null
        );

        assertThat(CanonicalHashCalculator.computeHash(req1))
                .isEqualTo(CanonicalHashCalculator.computeHash(req2));
    }

    @Test
    @DisplayName("Maps nested inside lists have key ordering normalized deterministically")
    void mapsNestedInsideLists_keyOrderingNormalized() {
        Map<String, Object> mapA1 = new LinkedHashMap<>();
        mapA1.put("k2", "v2");
        mapA1.put("k1", "v1");

        Map<String, Object> mapA2 = new LinkedHashMap<>();
        mapA2.put("k1", "v1");
        mapA2.put("k2", "v2");

        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, Map.of("items", List.of(mapA1)), null, null
        );

        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, Map.of("items", List.of(mapA2)), null, null
        );

        assertThat(CanonicalHashCalculator.computeHash(req1))
                .isEqualTo(CanonicalHashCalculator.computeHash(req2));
    }

    @Test
    @DisplayName("Changed relevant nested values produce different hashes")
    void changedRelevantNestedValues_produceDifferentHashes() {
        CreateNotificationRequest req1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, Map.of("orderId", 100), null, null
        );

        CreateNotificationRequest req2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null, Map.of("orderId", 101), null, null
        );

        assertThat(CanonicalHashCalculator.computeHash(req1))
                .isNotEqualTo(CanonicalHashCalculator.computeHash(req2));
    }

    @Test
    @DisplayName("Template requests compute deterministic hash preserving latest vs explicit version")
    void templateRequests_deterministicHashing() {
        UUID templateId = UUID.randomUUID();
        CreateNotificationRequest reqLatest1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                null, null,
                templateId, null,
                Map.of("name", "Alice"), null, null, null
        );

        CreateNotificationRequest reqLatest2 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                null, null,
                templateId, null,
                Map.of("name", "Alice"), null, null, null
        );

        CreateNotificationRequest reqExplicitV1 = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                null, null,
                templateId, 1,
                Map.of("name", "Alice"), null, null, null
        );

        // Same template request with null version yields identical hash across invocations (stable retry)
        assertThat(CanonicalHashCalculator.computeHash(reqLatest1))
                .isEqualTo(CanonicalHashCalculator.computeHash(reqLatest2));

        // Explicit version 1 differs from unversioned latest request
        assertThat(CanonicalHashCalculator.computeHash(reqExplicitV1))
                .isNotEqualTo(CanonicalHashCalculator.computeHash(reqLatest1));
    }

    @Test
    @DisplayName("Null request throws controlled VALIDATION_FAILED error")
    void nullRequest_throwsControlledError() {
        assertThatThrownBy(() -> CanonicalHashCalculator.computeHash(null))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                });
    }

    @Test
    @DisplayName("Serialization failure on unserializable metadata throws controlled VALIDATION_FAILED error and does not produce empty-data hash")
    void unserializableMetadata_throwsControlledValidationError() {
        // Supplying an Object with no serializable bean properties causes Jackson writeValueAsString
        // to throw InvalidDefinitionException (subclass of JsonProcessingException) under default FAIL_ON_EMPTY_BEANS
        Map<String, Object> invalidMetadata = Map.of("unserializable", new Object());
        CreateNotificationRequest req = new CreateNotificationRequest(
                Channel.EMAIL,
                "user@example.com",
                "Subject",
                "Body",
                null, null, null,
                invalidMetadata,
                null, null
        );

        assertThatThrownBy(() -> CanonicalHashCalculator.computeHash(req))
                .isInstanceOf(NotifyException.class)
                .satisfies(ex -> {
                    NotifyException ne = (NotifyException) ex;
                    assertThat(ne.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ne.getMessage()).contains("Failed to serialize canonical request descriptor");
                });
    }
}
