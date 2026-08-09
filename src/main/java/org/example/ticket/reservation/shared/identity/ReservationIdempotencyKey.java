package org.example.ticket.reservation.shared.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** 동기 예약과 Queue Worker가 공유하는 canonical client 멱등 키와 Redis 식별 hash다. */
public record ReservationIdempotencyKey(String value, String hash) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ReservationIdempotencyKey {
        String canonical = canonicalize(value);
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("idempotencyKey must be a lowercase canonical UUID");
        }
        if (hash == null || !SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException("idempotencyKeyHash must be a lowercase SHA-256 value");
        }
        if (!sha256(canonical).equals(hash)) {
            throw new IllegalArgumentException("idempotencyKeyHash does not match idempotencyKey");
        }
    }

    public static ReservationIdempotencyKey from(String rawKey) {
        String canonical = canonicalize(rawKey);
        return new ReservationIdempotencyKey(canonical, sha256(canonical));
    }

    public static ReservationIdempotencyKey restore(String canonicalKey, String storedHash) {
        return new ReservationIdempotencyKey(canonicalKey, storedHash);
    }

    private static String canonicalize(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        String candidate = rawKey.trim();
        try {
            String canonical = UUID.fromString(candidate).toString();
            if (!canonical.equalsIgnoreCase(candidate)) {
                throw new IllegalArgumentException("idempotencyKey must use canonical UUID format");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("idempotencyKey must use canonical UUID format", exception);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
