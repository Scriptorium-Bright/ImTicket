package org.example.ticket.reservation.waitingroom.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WaitingRoomPromotionSchedulerTest {

    /** 전역 feature flag가 꺼진 scheduler가 DB 회차 조회도 생략하는지 검증한다. */
    @Test
    void skipsPerformanceLookupWhenWaitingRoomIsDisabled() {
        WaitingRoomService service = mock(WaitingRoomService.class);
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(false);

        new WaitingRoomPromotionScheduler(properties, service, new SimpleMeterRegistry()).promoteActivePerformanceTimes();

        verifyNoInteractions(service);
    }

    /** 활성 회차를 조회해 Waiting Room promotion service로 전달하는지 검증한다. */
    @Test
    void promotesEnabledPerformanceTimes() {
        WaitingRoomService service = mock(WaitingRoomService.class);
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        properties.setEnabledPerformanceTimeIds(Set.of(7L));
        when(service.requiresWaitingRoom(7L)).thenReturn(true);

        new WaitingRoomPromotionScheduler(properties, service, new SimpleMeterRegistry()).promoteActivePerformanceTimes();

        verify(service).promote(7L);
    }
}
