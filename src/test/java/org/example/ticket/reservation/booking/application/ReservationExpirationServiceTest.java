package org.example.ticket.reservation.booking.application;

import org.example.ticket.reservation.booking.application.ReservationExpirationResult;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.persistence.ReservationRepository;
import org.example.ticket.reservation.booking.persistence.SeatRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ReservationExpirationService reservationExpirationService;

    @Test
    void preservesExpiredReservationAndReleasesAllLockedSeats() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 22, 0);
        Reservation reservation = Reservation.builder()
                .id(10L)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .expiredTime(now.minusMinutes(1))
                .build();
        Seat firstSeat = Seat.builder().id(11L).seatStatus(SeatStatus.LOCKED).build();
        Seat secondSeat = Seat.builder().id(12L).seatStatus(SeatStatus.LOCKED).build();

        when(reservationRepository.findExpiredReservationIdsBefore(
                ReservationStatus.PENDING_PAYMENT, now, PageRequest.of(0, 5000)))
                .thenReturn(List.of(10L));
        when(reservationRepository.findByIdInForUpdate(List.of(10L)))
                .thenReturn(List.of(reservation));
        when(seatRepository.findIdsByReservationIds(List.of(10L)))
                .thenReturn(List.of(11L, 12L));
        when(seatRepository.findByIdsForUpdate(List.of(11L, 12L)))
                .thenReturn(List.of(firstSeat, secondSeat));

        ReservationExpirationResult result =
                reservationExpirationService.expireReservations(now, 5000);

        assertThat(result).isEqualTo(new ReservationExpirationResult(1, 2));
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(firstSeat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(secondSeat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verify(reservationRepository, never()).deleteAll(List.of(reservation));
    }

    @Test
    void rechecksLockedReservationAndSkipsCompletionWinner() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 22, 0);
        Reservation completedReservation = Reservation.builder()
                .id(10L)
                .reservationStatus(ReservationStatus.SUCCESS)
                .expiredTime(now.minusMinutes(1))
                .build();

        when(reservationRepository.findExpiredReservationIdsBefore(
                ReservationStatus.PENDING_PAYMENT, now, PageRequest.of(0, 5000)))
                .thenReturn(List.of(10L));
        when(reservationRepository.findByIdInForUpdate(List.of(10L)))
                .thenReturn(List.of(completedReservation));

        ReservationExpirationResult result =
                reservationExpirationService.expireReservations(now, 5000);

        assertThat(result).isEqualTo(ReservationExpirationResult.empty());
        assertThat(completedReservation.getReservationStatus()).isEqualTo(ReservationStatus.SUCCESS);
        verify(seatRepository, never()).findIdsByReservationIds(List.of(10L));
    }

    @Test
    void treatsMissingExpirationAsExpiredInsteadOfLeakingLockedSeats() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 22, 0);
        Reservation reservation = Reservation.builder()
                .id(10L)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .expiredTime(null)
                .build();
        Seat seat = Seat.builder().id(11L).seatStatus(SeatStatus.LOCKED).build();

        when(reservationRepository.findExpiredReservationIdsBefore(
                ReservationStatus.PENDING_PAYMENT, now, PageRequest.of(0, 5000)))
                .thenReturn(List.of(10L));
        when(reservationRepository.findByIdInForUpdate(List.of(10L)))
                .thenReturn(List.of(reservation));
        when(seatRepository.findIdsByReservationIds(List.of(10L)))
                .thenReturn(List.of(11L));
        when(seatRepository.findByIdsForUpdate(List.of(11L)))
                .thenReturn(List.of(seat));

        ReservationExpirationResult result =
                reservationExpirationService.expireReservations(now, 5000);

        assertThat(result).isEqualTo(new ReservationExpirationResult(1, 1));
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }
}
