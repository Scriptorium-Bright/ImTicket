package org.example.ticket.reservation.booking.cache;

import java.util.List;
import java.util.Map;

/** Redis의 정적 layout과 동적 availability를 함께 읽은 일관성 있는 좌석 읽기 모델이다. */
public record SeatMapCacheParts(
        long layoutGeneration,
        List<SeatLayoutCacheEntry> layoutEntries,
        long availabilityGeneration,
        Map<Long, SeatAvailabilityCacheEntry> availabilityEntries
) {

    /** 조회 결과의 두 목록과 상태 map을 불변 컬렉션으로 복사한다.
     * 응답 조합 중 owner·joiner가 동일한 읽기 모델을 안전하게 참조하게 한다. */
    public SeatMapCacheParts {
        layoutEntries = List.copyOf(layoutEntries);
        availabilityEntries = Map.copyOf(availabilityEntries);
    }
}
