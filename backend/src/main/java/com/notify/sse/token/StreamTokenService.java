package com.notify.sse.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.sse.config.SseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-use SSE stream tokens (CONTRACTS §SSE, §Redis; / ).
 *
 * <p>Mint: 32 CSPRNG bytes, base64url, stored at {@code sse:token:{token}} with {@code SET NX} and the contracted
 * 60 s TTL. The value holds the tenant and recipient binding only — no secret, no body.
 *
 * <p>Consume: one atomic {@code GETDEL}, so two concurrent connections presenting the same token cannot both
 * succeed; the loser gets {@code 401 STREAM_TOKEN_INVALID}. A {@code GET} followed by a {@code DEL} would be a race.
 * Everything here is <b>fail-closed</b>: if Redis is unavailable a token can be neither minted nor verified, so no
 * stream opens.
 *
 * <p>Token values never appear in logs, metrics, traces or error bodies.
 */
@Service
public class StreamTokenService {

    private static final Logger log = LoggerFactory.getLogger(StreamTokenService.class);
    private static final String KEY_PREFIX = "sse:token:";

    private final StringRedisTemplate redis;
    private final SseProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public StreamTokenService(StringRedisTemplate redis, SseProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** @return the one-time token, or empty when Redis cannot store it (fail-closed). */
    public Optional<String> mint(UUID tenantId, String recipientRef) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try {
            String value = mapper.writeValueAsString(
                    Map.of("tenantId", tenantId.toString(), "recipient", recipientRef));
            Boolean stored = redis.opsForValue().setIfAbsent(KEY_PREFIX + token, value, props.tokenTtl());
            if (!Boolean.TRUE.equals(stored)) {
                return Optional.empty();
            }
            return Optional.of(token);
        } catch (Exception e) {
            log.warn("Stream token could not be minted ({})", e.getClass().getName());
            return Optional.empty();
        }
    }

    /** Atomically consumes the token. Empty means invalid, already used, expired, or Redis unavailable. */
    public Optional<StreamToken> consume(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().getAndDelete(KEY_PREFIX + token);
        } catch (Exception e) {
            log.warn("Stream token could not be verified; failing closed ({})", e.getClass().getName());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            Map<?, ?> parsed = mapper.readValue(value, Map.class);
            Object tenant = parsed.get("tenantId");
            Object recipient = parsed.get("recipient");
            if (tenant == null || recipient == null) {
                return Optional.empty();
            }
            return Optional.of(new StreamToken(UUID.fromString(tenant.toString()), recipient.toString()));
        } catch (Exception e) {
            log.warn("Stream token payload was unreadable ({})", e.getClass().getName());
            return Optional.empty();
        }
    }
}
