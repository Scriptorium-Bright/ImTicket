package org.example.ticket.reservation.util;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.dto.ReservationRequestFingerprint;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.validation.ReservationValidator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class ReservationRequestHasher {

    private static final String SCHEMA = "reservation-pre-reserve:v1";

    /**
     * 외부 멱등성 키를 공백 없는 canonical UUID 문자열로 정규화한다.
     * UUID 형식이 아니거나 canonical 표현이 아니면 같은 요청으로 잘못 묶이지 않도록 거절한다.
     */
    public String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String candidate = rawKey.trim();
        try {
            String normalized = UUID.fromString(candidate).toString();
            if (!normalized.equalsIgnoreCase(candidate)) {
                throw new IllegalArgumentException("UUID canonical form이 아닙니다.");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID, exception);
        }
    }

    /**
     * 예매 요청을 검증하고 좌석 ID 순서에 영향받지 않는 canonical 본문과 SHA-256 해시를 만든다.
     * 정렬 전후 목록을 비교해 중복 좌석 요청도 이 단계에서 차단한다.
     */
    public ReservationRequestFingerprint fingerprint(ReservationRequest request) {
        ReservationValidator.validateCreateRequest(request);
        if (request.getSeatIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ReservationErrorCode.INVALID_SEAT_ID);
        }

        List<Long> normalizedSeatIds = request.getSeatIds().stream().sorted().toList();
        ReservationValidator.validateNoDuplicateSeatIds(request.getSeatIds(), normalizedSeatIds.stream().distinct().toList());

        String canonical = SCHEMA + "\n"
                + "performanceTimeId=" + request.getPerformanceTimeId() + "\n"
                + "seatIds=" + normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return new ReservationRequestFingerprint(sha256(canonical), normalizedSeatIds);
    }

    /** UTF-8 canonical 본문을 SHA-256으로 해싱해 비교·저장에 사용할 16진수 문자열로 변환한다. */
    private String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
