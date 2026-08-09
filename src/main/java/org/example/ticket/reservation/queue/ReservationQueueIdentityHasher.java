package org.example.ticket.reservation.queue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** 외부 식별자를 정규화한 뒤 Redis key에 사용할 SHA-256 값으로 변환한다. */
public final class ReservationQueueIdentityHasher {

    public String ownerHash(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("walletAddress must not be blank");
        }
        return sha256(walletAddress.trim().toLowerCase(Locale.ROOT));
    }

    public String idempotencyKeyHash(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        String candidate = rawKey.trim();
        try {
            String canonical = UUID.fromString(candidate).toString();
            if (!canonical.equalsIgnoreCase(candidate)) {
                throw new IllegalArgumentException("idempotencyKey must use canonical UUID format");
            }
            return sha256(canonical);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("idempotencyKey must use canonical UUID format", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
