package org.example.ticket.reservation.booking.cache;

import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.util.constant.SeatStatus;

/** Redis availability Hash에 저장되는 좌석 상태와 JPA version이다. */
public record SeatAvailabilityCacheEntry(
        Long seatId,
        SeatStatus seatStatus,
        Long seatVersion
) {

    /** null version을 초기 version 0으로 정규화한다.
     * 기존 데이터와 새 projection이 같은 비교 규칙을 사용하게 한다. */
    public SeatAvailabilityCacheEntry {
        if (seatVersion == null) {
            seatVersion = 0L;
        }
    }

    /** 기존 응답에서 동적 상태를 복사한다.
     * DB version을 알 수 없는 호환 경로는 0을 사용한다. */
    public static SeatAvailabilityCacheEntry from(SeatResponse response) {
        return new SeatAvailabilityCacheEntry(response.getId(), response.getSeatStatus(), 0L);
    }
}
