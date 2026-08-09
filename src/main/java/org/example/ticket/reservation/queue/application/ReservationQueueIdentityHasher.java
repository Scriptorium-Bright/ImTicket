package org.example.ticket.reservation.queue.application;

import org.example.ticket.reservation.shared.identity.ReservationIdempotencyKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 외부 식별자를 정규화한 뒤 Redis key에 사용할 SHA-256 값으로 변환한다. */
public final class ReservationQueueIdentityHasher {

    public String ownerHash(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("walletAddress must not be blank");
        }
        return sha256(walletAddress.trim().toLowerCase(Locale.ROOT));
    }

    public String idempotencyKeyHash(String rawKey) {
        return idempotencyKey(rawKey).hash();
    }

    public ReservationIdempotencyKey idempotencyKey(String rawKey) {
        return ReservationIdempotencyKey.from(rawKey);
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
