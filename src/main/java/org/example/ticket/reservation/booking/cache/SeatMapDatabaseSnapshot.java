package org.example.ticket.reservation.booking.cache;

import java.util.List;

/** 하나의 read-only transaction에서 읽은 layout·availability projection이다. */
public record SeatMapDatabaseSnapshot(
        List<SeatLayoutCacheEntry> layoutEntries,
        List<SeatAvailabilityCacheEntry> availabilityEntries
) {

    /** DB projection 목록을 불변 컬렉션으로 복사한다.
     * split cache 저장과 API 응답 조합의 입력을 동일하게 유지한다. */
    public SeatMapDatabaseSnapshot {
        layoutEntries = List.copyOf(layoutEntries);
        availabilityEntries = List.copyOf(availabilityEntries);
    }
}
