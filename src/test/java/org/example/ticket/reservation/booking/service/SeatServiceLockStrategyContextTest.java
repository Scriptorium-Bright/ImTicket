package org.example.ticket.reservation.booking.service;

import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.util.lock.ReservationLockStrategy;
import org.example.ticket.reservation.booking.util.lock.ReservationLockStrategyContext;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatServiceLockStrategyContextTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private PerformanceTimeRepository performanceTimeRepository;

    @Mock
    private VenueHallSeatTemplateRepository seatTemplateRepository;

    private ReservationLockStrategyContext strategyContext;
    private SeatService seatService;

    @BeforeEach
    void setUp() {
        strategyContext = new ReservationLockStrategyContext();
        seatService = new SeatService(
                seatRepository,
                performanceTimeRepository,
                seatTemplateRepository,
                strategyContext
        );
    }

    @Test
    void usesPessimisticQueryWhenAnnotationContextSelectsPessimistic() throws Throwable {
        List<Long> seatIds = List.of(1L);
        List<Seat> seats = List.of(mock(Seat.class));
        when(seatRepository.findByPerformanceTimeIdAndIdsForUpdate(10L, seatIds)).thenReturn(seats);

        List<Seat> result = strategyContext.withStrategy(
                ReservationLockStrategy.PESSIMISTIC,
                () -> seatService.findAndLockSeatsByPerformanceTime(10L, seatIds)
        );

        assertThat(result).isEqualTo(seats);
        verify(seatRepository).findByPerformanceTimeIdAndIdsForUpdate(10L, seatIds);
    }

    @Test
    void usesNormalQueryWhenAnnotationContextSelectsOptimistic() throws Throwable {
        List<Long> seatIds = List.of(1L);
        List<Seat> seats = List.of(mock(Seat.class));
        when(seatRepository.findByPerformanceTimeIdAndIds(10L, seatIds)).thenReturn(seats);

        List<Seat> result = strategyContext.withStrategy(
                ReservationLockStrategy.OPTIMISTIC,
                () -> seatService.findAndLockSeatsByPerformanceTime(10L, seatIds)
        );

        assertThat(result).isEqualTo(seats);
        verify(seatRepository).findByPerformanceTimeIdAndIds(10L, seatIds);
    }

    @Test
    void missingSeatInPerformanceTimeReturnsStableFinalError() throws Throwable {
        List<Long> seatIds = List.of(1L, 2L);
        when(seatRepository.findByPerformanceTimeIdAndIds(10L, seatIds))
                .thenReturn(List.of(mock(Seat.class)));

        assertThatThrownBy(() -> strategyContext.withStrategy(
                ReservationLockStrategy.OPTIMISTIC,
                () -> seatService.findAndLockSeatsByPerformanceTime(10L, seatIds)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.RESERVATION_SEAT_NOT_FOUND));
    }
}
