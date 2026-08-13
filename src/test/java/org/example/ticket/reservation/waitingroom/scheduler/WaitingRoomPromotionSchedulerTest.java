package org.example.ticket.reservation.waitingroom.scheduler;

import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WaitingRoomPromotionSchedulerTest {

    /** 전역 feature flag가 꺼진 scheduler가 DB 회차 조회도 생략하는지 검증한다. */
    @Test
    void skipsPerformanceLookupWhenWaitingRoomIsDisabled() {
        PerformanceTimeRepository repository = mock(PerformanceTimeRepository.class);
        WaitingRoomService service = mock(WaitingRoomService.class);
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(false);

        new WaitingRoomPromotionScheduler(repository, properties, service).promoteActivePerformanceTimes();

        verifyNoInteractions(repository, service);
    }

    /** 활성 회차를 조회해 Waiting Room promotion service로 전달하는지 검증한다. */
    @Test
    void promotesEnabledPerformanceTimes() {
        PerformanceTimeRepository repository = mock(PerformanceTimeRepository.class);
        WaitingRoomService service = mock(WaitingRoomService.class);
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        when(repository.findAll()).thenReturn(List.of(PerformanceTime.builder().id(7L).build()));
        when(service.requiresWaitingRoom(7L)).thenReturn(true);

        new WaitingRoomPromotionScheduler(repository, properties, service).promoteActivePerformanceTimes();

        verify(service).promote(7L);
    }
}
