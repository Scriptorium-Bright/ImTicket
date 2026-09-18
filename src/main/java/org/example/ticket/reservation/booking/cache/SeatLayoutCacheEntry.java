package org.example.ticket.reservation.booking.cache;

import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;

/** Redis layout 값에 저장되는 좌석 배치 정보다. 예약 진행 중에는 변경되지 않는 필드만 포함한다. */
public record SeatLayoutCacheEntry(
        Long id,
        Integer seatFloor,
        String seatSection,
        Integer seatRow,
        Integer seatNumber,
        SeatInfo seatType,
        Integer price,
        Boolean isReservation
) {

    /** 기존 좌석 조회 응답에서 정적 배치 필드를 복사한다.
     * 캐시의 layout 모델이 API 응답 구현에 직접 의존하지 않도록 변환한다. */
    public static SeatLayoutCacheEntry from(SeatResponse response) {
        return new SeatLayoutCacheEntry(
                response.getId(),
                response.getSeatFloor(),
                response.getSeatSection(),
                response.getSeatRow(),
                response.getSeatNumber(),
                response.getSeatType(),
                response.getPrice(),
                response.getIsReservation()
        );
    }

    /** 동적 상태를 조합해 기존 API 응답을 만든다.
     * 정적 배치와 동적 상태를 API contract의 필드 순서로 복원한다. */
    public SeatResponse toResponse(SeatStatus status) {
        return new SeatResponse(
                id,
                seatFloor,
                seatSection,
                seatRow,
                seatNumber,
                seatType,
                price,
                isReservation,
                status
        );
    }
}
