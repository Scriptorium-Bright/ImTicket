package org.example.ticket.reservation.waitingroom.service;

import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWaitingRoomFeaturePolicyTest {

    /** 전역 flag가 꺼진 경우 모든 회차의 Waiting Room이 비활성화되는지 검증한다. */
    @Test
    void disablesAllPerformanceTimesWhenGlobalFlagIsOff() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(false);

        assertThat(new DefaultWaitingRoomFeaturePolicy(properties).requiresWaitingRoom(7L)).isFalse();
    }

    /** 선택 회차 목록이 있을 때 지정된 회차만 활성화되는지 검증한다. */
    @Test
    void limitsEnabledFeatureToConfiguredPerformanceTimes() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        properties.setEnabledPerformanceTimeIds(Set.of(7L, 8L));
        DefaultWaitingRoomFeaturePolicy policy = new DefaultWaitingRoomFeaturePolicy(properties);

        assertThat(policy.requiresWaitingRoom(7L)).isTrue();
        assertThat(policy.requiresWaitingRoom(9L)).isFalse();
    }
}
