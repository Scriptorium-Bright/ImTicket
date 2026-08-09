package org.example.ticket.reservation.booking.service;

import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatServiceSeatMapTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private PerformanceTimeRepository performanceTimeRepository;

    @Mock
    private VenueHallSeatTemplateRepository seatTemplateRepository;

    @InjectMocks
    private SeatService seatService;

    @Test
    void viewSeatMapReturnsSeatStatusFromRepositoryProjection() {
        Long performanceTimeId = 1L;
        SeatResponse lockedSeat = SeatResponse.builder()
                .id(10L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(5)
                .seatType(SeatInfo.VIP)
                .price(100000)
                .isReservation(false)
                .seatStatus(SeatStatus.LOCKED)
                .build();

        when(seatRepository.findSeatMapByPerformanceTimeId(performanceTimeId))
                .thenReturn(List.of(lockedSeat));

        List<SeatResponse> responses = seatService.viewSeatMap(performanceTimeId);

        assertThat(responses)
                .hasSize(1)
                .first()
                .extracting(SeatResponse::getSeatStatus)
                .isEqualTo(SeatStatus.LOCKED);
        verify(seatRepository).findSeatMapByPerformanceTimeId(performanceTimeId);
    }
}
