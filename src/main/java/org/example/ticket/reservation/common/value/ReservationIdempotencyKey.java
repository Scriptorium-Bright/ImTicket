package org.example.ticket.reservation.common.value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** 동기 예약과 Queue Worker가 공유하는 canonical client 멱등 키와 Redis 식별 hash다. */
public record ReservationIdempotencyKey(String value, String hash) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * canonical UUID와 저장된 SHA-256 hash의 일치 여부를 검증한다.
     * 유효한 값만 동기 예약과 Queue의 공통 멱등 식별자로 유지한다.
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
     * 클라이언트가 보낸 UUID를 canonical 형식과 hash로 변환한다.
     * 예약 접수 경로가 DB key와 Redis key 재료를 함께 얻는다.
     */
    public static ReservationIdempotencyKey from(String rawKey) {
        String canonical = canonicalize(rawKey);
        return new ReservationIdempotencyKey(canonical, sha256(canonical));
    }

    /**
     * 저장소에서 읽은 canonical key와 hash를 공통 값으로 복원한다.
     * 생성자 검증을 다시 적용해 손상되거나 불일치한 payload를 거절한다.
     */
    public static ReservationIdempotencyKey restore(String canonicalKey, String storedHash) {
        return new ReservationIdempotencyKey(canonicalKey, storedHash);
    }

    /**
     * 입력 UUID의 공백과 대소문자를 표준 문자열 형식으로 정리한다.
     * UUID 형식이 정확하지 않으면 예약 처리 전에 예외를 발생시킨다.
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
     * canonical 멱등 키의 소문자 SHA-256 값을 계산한다.
     * 원문 키를 Redis key에 직접 포함하지 않도록 해시 식별자를 제공한다.
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
