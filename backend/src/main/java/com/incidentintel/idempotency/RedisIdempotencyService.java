package com.incidentintel.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
}
