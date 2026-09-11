package org.example.ticket.reservation.booking.cache;

import java.util.List;

/** Redis에 저장되는 회차별 좌석 snapshot과 생성 version을 함께 표현한다. */
public record SeatMapCacheSnapshot(
        long version,
        List<SeatMapCacheEntry> entries
) {

    /**
     * snapshot 항목을 불변 목록으로 복사한다.
     * owner와 joiner가 동일한 결과 데이터를 안전하게 참조하게 한다.
     */
    public SeatMapCacheSnapshot {
        entries = List.copyOf(entries);
    }
}
