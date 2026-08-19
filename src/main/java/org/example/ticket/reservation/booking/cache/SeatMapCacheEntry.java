package org.example.ticket.reservation.booking.cache;

import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;

/** Redis JSON 역직렬화를 위해 사용하는 불변 좌석 snapshot 항목이다. */
public record SeatMapCacheEntry(
        Long id,
        Integer seatFloor,
        String seatSection,
        Integer seatRow,
        Integer seatNumber,
        SeatInfo seatType,
        Integer price,
        Boolean isReservation,
        SeatStatus seatStatus
    ) {

    /**
     * API 응답 DTO의 좌석 상태를 불변 cache entry로 복사한다.
     * JSON cache가 영속 entity나 API DTO 구현에 결합되지 않게 한다.
     */
    public static SeatMapCacheEntry from(SeatResponse response) {
        return new SeatMapCacheEntry(
                response.getId(),
                response.getSeatFloor(),
                response.getSeatSection(),
                response.getSeatRow(),
                response.getSeatNumber(),
                response.getSeatType(),
                response.getPrice(),
                response.getIsReservation(),
                response.getSeatStatus()
        );
    }

    /**
     * cache entry를 API 응답 DTO로 복원한다.
     * cache hit 응답의 필드와 DB projection 응답의 필드를 동일하게 유지한다.
     */
    public SeatResponse toResponse() {
        return new SeatResponse(
                id,
                seatFloor,
                seatSection,
                seatRow,
                seatNumber,
                seatType,
                price,
                isReservation,
                seatStatus
        );
    }
}
