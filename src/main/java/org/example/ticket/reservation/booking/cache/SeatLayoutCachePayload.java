package org.example.ticket.reservation.booking.cache;

import java.util.List;

/** Redis layout String에 저장되는 generation과 정적 좌석 목록이다. */
public record SeatLayoutCachePayload(
        long generation,
        List<SeatLayoutCacheEntry> entries
) {

    /** Redis에 저장되는 좌석 목록을 불변 목록으로 복사한다.
     * 캐시 작성 중 호출자의 가변 목록 변경이 payload에 영향을 주지 않게 한다. */
    public SeatLayoutCachePayload {
        entries = List.copyOf(entries);
    }
}
