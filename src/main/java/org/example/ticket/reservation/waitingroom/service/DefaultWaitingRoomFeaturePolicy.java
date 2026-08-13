package org.example.ticket.reservation.waitingroom.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;

/** 전역 flag와 선택 회차 목록으로 Waiting Room 적용 여부를 판정한다. */
@RequiredArgsConstructor
public final class DefaultWaitingRoomFeaturePolicy implements WaitingRoomFeaturePolicy {

    private final WaitingRoomProperties properties;

    /** 활성화된 회차 목록과 전역 flag를 조합해 보호 구간 적용 여부를 반환한다.
     * 회차 목록이 비어 있으면 전역 활성화 범위를 모든 회차로 해석한다. */
    @Override
    public boolean requiresWaitingRoom(long performanceTimeId) {
        if (!properties.isEnabled()) {
            return false;
        }
        return properties.getEnabledPerformanceTimeIds().isEmpty()
                || properties.getEnabledPerformanceTimeIds().contains(performanceTimeId);
    }
}
