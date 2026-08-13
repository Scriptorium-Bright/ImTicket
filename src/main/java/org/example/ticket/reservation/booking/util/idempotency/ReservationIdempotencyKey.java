package org.example.ticket.reservation.booking.util.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** 동기 예약 요청의 canonical client 멱등 키와 비교용 hash다. */
public record ReservationIdempotencyKey(String value, String hash) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * canonical UUID와 저장된 SHA-256 hash의 일치 여부를 검증한다.
     * 유효한 값만 예약 요청의 멱등 식별자로 유지한다.
     */
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

    /**
     * 외부 UUID를 canonical 형식과 SHA-256 hash로 정규화한다.
     * 같은 논리 요청을 DB claim key 하나로 수렴시키는 값이다.
     */
    public static ReservationIdempotencyKey from(String rawKey) {
        String canonical = canonicalize(rawKey);
        return new ReservationIdempotencyKey(canonical, sha256(canonical));
    }

    /**
     * 저장된 canonical key와 hash를 검증해 복원한다.
     * 손상되거나 불일치한 멱등 key payload를 거절한다.
     */
    public static ReservationIdempotencyKey restore(String canonicalKey, String storedHash) {
        return new ReservationIdempotencyKey(canonicalKey, storedHash);
    }

    /**
     * 입력 UUID를 소문자 canonical 문자열로 변환한다.
     * UUID 형식이 정확하지 않으면 예약 실행 전에 예외를 발생시킨다.
     */
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

    /**
     * canonical UUID의 소문자 SHA-256 hash를 계산한다.
     * key 원문을 외부 식별자에 직접 쓰지 않도록 비교용 값을 제공한다.
     */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
