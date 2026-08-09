package org.example.ticket.reservation.booking.application;

import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.model.SeatPrice;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.persistence.SeatRepository;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.example.ticket.venue.model.VenueHall;
import org.example.ticket.venue.model.VenueHallSeatTemplate;
import org.example.ticket.venue.repository.VenueHallSeatTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatServiceTemplateTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private PerformanceTimeRepository performanceTimeRepository;

    @Mock
    private VenueHallSeatTemplateRepository seatTemplateRepository;

    @InjectMocks
    private SeatService seatService;

    @Test
    void preprocessSeatDataCreatesPerformanceSeatsFromFlatTemplates() {
        VenueHall hall = VenueHall.builder()
                .id(1L)
                .name("main hall")
                .build();
        Performance performance = Performance.builder()
                .title("show")
                .build();
        SeatPrice price = SeatPrice.builder()
                .seatInfo(SeatInfo.VIP)
                .price(10000)
                .build();
        performance.addPrice(price);
        PerformanceTime performanceTime = PerformanceTime.builder()
                .id(10L)
                .venueHall(hall)
                .performance(performance)
                .build();
        List<VenueHallSeatTemplate> templates = List.of(
                VenueHallSeatTemplate.builder()
                        .venueHall(hall)
                        .floor(1)
                        .section("A")
                        .row(1)
                        .seatNumber(1)
                        .seatInfo(SeatInfo.VIP)
                        .build(),
                VenueHallSeatTemplate.builder()
                        .venueHall(hall)
                        .floor(1)
                        .section("A")
                        .row(1)
                        .seatNumber(2)
                        .seatInfo(SeatInfo.VIP)
                        .build()
        );
        ArgumentCaptor<List<Seat>> captor = ArgumentCaptor.forClass(List.class);

        when(performanceTimeRepository.findById(10L)).thenReturn(Optional.of(performanceTime));
        when(seatTemplateRepository.findActiveTemplatesByHallId(1L)).thenReturn(templates);

        seatService.preprocessSeatData(10L, null).join();

        verify(seatRepository).saveAll(captor.capture());

        List<Seat> seats = captor.getValue();
        assertThat(seats).hasSize(2);
        assertThat(seats)
                .extracting(Seat::getSeatNumber)
                .containsExactly(1, 2);
        assertThat(seats)
                .allSatisfy(seat -> {
                    assertThat(seat.getPerformanceTime()).isSameAs(performanceTime);
                    assertThat(seat.getSeatFloor()).isEqualTo(1);
                    assertThat(seat.getSeatSection()).isEqualTo("A");
                    assertThat(seat.getSeatRow()).isEqualTo(1);
                    assertThat(seat.getSeatType()).isEqualTo(SeatInfo.VIP);
                    assertThat(seat.getPrice()).isEqualTo(10000);
                    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
                });
    }
}
