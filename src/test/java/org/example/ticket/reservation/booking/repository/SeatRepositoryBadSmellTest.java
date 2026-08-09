package org.example.ticket.reservation.booking.repository;

import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
public class SeatRepositoryBadSmellTest {

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private PerformanceTimeRepository performanceTimeRepository;

    @Test
    @DisplayName("좌석 lock 조회: performanceTimeId 조건으로 다른 회차의 동일 위치 좌석을 제외한다")
    public void findSeatsForUpdateWithinPerformanceTime() {
        PerformanceTime time1 = PerformanceTime.builder()
                .showDate(LocalDate.now())
                .showTime(LocalTime.NOON)
                .build();
        PerformanceTime time2 = PerformanceTime.builder()
                .showDate(LocalDate.now())
                .showTime(LocalTime.NOON.plusHours(1))
                .build();
        performanceTimeRepository.save(time1);
        performanceTimeRepository.save(time2);

        Seat seat1 = Seat.builder()
                .performanceTime(time1)
                .seatFloor(1).seatSection("A").seatRow(1).seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(10000)
                .isReservation(false)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();

        Seat seat2 = Seat.builder()
                .performanceTime(time2)
                .seatFloor(1).seatSection("A").seatRow(1).seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(10000)
                .isReservation(false)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();

        seatRepository.save(seat1);
        seatRepository.save(seat2);

        List<Seat> seats = seatRepository.findByPerformanceTimeIdAndIdsForUpdate(time1.getId(), List.of(seat1.getId(), seat2.getId()));

        assertThat(seats)
                .hasSize(1)
                .extracting(Seat::getId)
                .containsExactly(seat1.getId());
    }
}
