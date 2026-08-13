package org.example.ticket.reservation.waitingroom.pass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256으로 Waiting Room entry pass를 서명하고 검증한다. */
public final class HmacWaitingRoomPassCodec implements WaitingRoomPassCodec {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec secretKey;

    /** 외부 설정 secret으로 entry pass codec을 구성한다.
     * 빈 secret은 애플리케이션 구성 오류로 즉시 거절한다. */
    public HmacWaitingRoomPassCodec(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** claim payload에 HMAC 서명을 추가한 URL-safe pass를 발급한다.
     * payload에는 ticket·회원·회차와 issued·expiry 시각을 포함한다. */
    @Override
    public String issue(WaitingRoomPassClaims claims) {
        if (claims == null) {
            throw new IllegalArgumentException("claims must not be null");
        }
        String payload = String.join(
                ".",
                VERSION,
                claims.ticketId().toString(),
                Long.toString(claims.memberId()),
                Long.toString(claims.performanceTimeId()),
                Long.toString(claims.issuedAt().toEpochMilli()),
                Long.toString(claims.expiresAt().toEpochMilli())
        );
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = encode(sign(payload.getBytes(StandardCharsets.UTF_8)));
        return encodedPayload + "." + encodedSignature;
    }

    /** URL-safe pass의 서명과 payload를 검증해 claim으로 복원한다.
     * 서명·형식 오류는 유효하지 않은 pass 예외로 전달한다. */
    @Override
    public WaitingRoomPassClaims parse(String token) {
        try {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("token must not be blank");
            }
            String[] tokenParts = token.split("\\.", -1);
            if (tokenParts.length != 2) {
                throw new IllegalArgumentException("token format is invalid");
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(tokenParts[0]);
            byte[] receivedSignature = Base64.getUrlDecoder().decode(tokenParts[1]);
            byte[] expectedSignature = sign(payloadBytes);
            if (!MessageDigest.isEqual(expectedSignature, receivedSignature)) {
                throw new IllegalArgumentException("token signature is invalid");
            }
            String[] claims = new String(payloadBytes, StandardCharsets.UTF_8).split("\\.", -1);
            if (claims.length != 6 || !VERSION.equals(claims[0])) {
                throw new IllegalArgumentException("token payload is invalid");
            }
            return new WaitingRoomPassClaims(
                    UUID.fromString(claims[1]),
                    Long.parseLong(claims[2]),
                    Long.parseLong(claims[3]),
                    Instant.ofEpochMilli(Long.parseLong(claims[4])),
                    Instant.ofEpochMilli(Long.parseLong(claims[5]))
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("token payload is invalid", exception);
        }
    }

    /** HMAC-SHA256으로 payload를 서명한다.
     * codec 생성 시 고정한 secret key를 모든 검증에 사용한다. */
    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC signing is unavailable", exception);
        }
    }

    /** payload와 signature를 URL-safe Base64 문자열로 변환한다.
     * URL path와 HTTP header에서 안전하게 전달되도록 padding을 제거한다. */
    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
