package org.example.ticket.reservation.booking.util;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.dto.request.ReservationRequest;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.common.value.ReservationIntentFingerprint;
import org.springframework.stereotype.Component;

import java.util.HashSet;

@Component
public class ReservationRequestHasher {

    /**
     * 외부 멱등성 키를 공백 없는 canonical UUID 문자열로 정규화한다.
     * UUID 형식이 아니거나 canonical 표현이 아니면 같은 요청으로 잘못 묶이지 않도록 거절한다.
     */
    public String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        try {
            return ReservationIdempotencyKey.from(rawKey).value();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReservationErrorCode.IDEMPOTENCY_KEY_INVALID, exception);
        }
    }

    /**
     * 예매 요청을 검증하고 좌석 ID 순서에 영향받지 않는 canonical 본문과 SHA-256 해시를 만든다.
     * 정렬 전후 목록을 비교해 중복 좌석 요청도 이 단계에서 차단한다.
     */
    public ReservationIntentFingerprint fingerprint(ReservationRequest request) {
        ReservationValidator.validateCreateRequest(request);
        if (request.getSeatIds().stream().anyMatch(seatId -> seatId == null || seatId <= 0)) {
            throw new BusinessException(ReservationErrorCode.INVALID_SEAT_ID);
        }
        if (new HashSet<>(request.getSeatIds()).size() != request.getSeatIds().size()) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_SEAT_INCLUDED);
        }

        ReservationIntentFingerprint fingerprint = ReservationIntentFingerprintFactory.create(
                request.getPerformanceTimeId(),
                request.getSeatIds()
        );
        return fingerprint;
    }
}
