package com.incidentintel.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the idempotency key for an incoming webhook: the PagerDuty
 * {@code dedup_key} if present, else a stable hash of
 * routing_key+source+component+class+summary.
 */
public final class IdempotencyKeyGenerator {

    private IdempotencyKeyGenerator() {
    }

    public static String generate(String dedupKey, String routingKey, String source,
                                   String component, String className, String summary) {
        if (dedupKey != null && !dedupKey.isBlank()) {
            return dedupKey;
        }
        String raw = String.join("|",
                nullToEmpty(routingKey), nullToEmpty(source), nullToEmpty(component),
                nullToEmpty(className), nullToEmpty(summary));
        return sha256Hex(raw);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
