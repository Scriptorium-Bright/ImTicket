package org.example.ticket.reservation.queue.util;

import org.example.ticket.reservation.common.domain.ReservationIdempotencyKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 외부 식별자를 정규화한 뒤 Redis key에 사용할 SHA-256 값으로 변환한다. */
public final class ReservationQueueIdentityHasher {

    /**
     * wallet 주소를 trim과 소문자 정규화 후 SHA-256으로 변환한다.
     * Queue 소유자 비교가 주소 대소문자에 영향을 받지 않게 한다.
     */
    public String ownerHash(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("walletAddress must not be blank");
        }
        return sha256(walletAddress.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * client 멱등 키를 canonical UUID로 검증하고 hash만 반환한다.
     * Redis mapping key를 만들 때 원문 UUID 사용을 피하게 한다.
     */
    public String idempotencyKeyHash(String rawKey) {
        return idempotencyKey(rawKey).hash();
    }

    /**
     * client 멱등 키를 공통 canonical 값으로 변환한다.
     * Worker용 원문 UUID와 Redis용 hash를 한 객체로 반환한다.
     */
    public ReservationIdempotencyKey idempotencyKey(String rawKey) {
        return ReservationIdempotencyKey.from(rawKey);
    }

    /**
     * 정규화된 식별자를 소문자 SHA-256 문자열로 계산한다.
     * 런타임에 알고리즘을 제공할 수 없으면 설정 오류로 처리한다.
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
