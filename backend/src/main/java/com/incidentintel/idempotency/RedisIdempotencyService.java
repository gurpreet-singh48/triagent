package com.incidentintel.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed idempotency guard: SETNX {@code idem:<key>} -> incidentId,
 * TTL 24h. The first caller to reserve a given key wins and proceeds to
 * create the Incident; every other caller within the TTL window is a
 * duplicate and should be routed back to the incident the winner created,
 * without ever reaching the agent service.
 */
@Service
public class RedisIdempotencyService {

    private static final String KEY_PREFIX = "idem:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Returns true if this call reserved the key (i.e. is the first/winning request). */
    public boolean tryReserve(String idempotencyKey, UUID incidentId) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + idempotencyKey, incidentId.toString(), TTL);
        return Boolean.TRUE.equals(result);
    }

    public Optional<UUID> getExistingIncidentId(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        return Optional.ofNullable(value).map(UUID::fromString);
    }

    /**
     * Releases a reservation only if it is still owned by {@code incidentId}.
     * The compare-and-delete is atomic, so compensation cannot delete a
     * reservation that was replaced by another request.
     */
    public boolean release(String idempotencyKey, UUID incidentId) {
        Long deleted = redisTemplate.execute(
                RELEASE_IF_OWNER,
                Collections.singletonList(KEY_PREFIX + idempotencyKey),
                incidentId.toString());
        return deleted != null && deleted == 1L;
    }
}
