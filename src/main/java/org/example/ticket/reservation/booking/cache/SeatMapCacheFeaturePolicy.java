package org.example.ticket.reservation.booking.cache;

import lombok.RequiredArgsConstructor;

/** 설정과 대상 회차 목록을 조합해 cache 적용 여부를 판정한다. */
@RequiredArgsConstructor
public final class SeatMapCacheFeaturePolicy {

    private final SeatMapCacheProperties properties;

    /**
     * 전역 flag와 명시된 회차 목록이 모두 일치하는지 판정한다.
     * 대상 목록이 비어 있으면 안전한 기본값으로 cache를 사용하지 않는다.
     */
    public boolean appliesTo(long performanceTimeId) {
        return properties.isEnabled()
                && properties.getEnabledPerformanceTimeIds().contains(performanceTimeId);
    }
}
