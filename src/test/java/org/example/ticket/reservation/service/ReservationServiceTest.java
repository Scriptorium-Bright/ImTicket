package org.example.ticket.reservation.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.request.ReservationCheckRequest;
import org.example.ticket.util.tracing.TracingConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.example.ticket.util.constant.ReservationStatus.PENDING_PAYMENT;
import static org.example.ticket.util.constant.SeatStatus.AVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private SeatService seatService;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void confirmReservationRejectsDifferentOwner() {
        Member owner = Member.builder()
                .walletAddress("0xowner")
                .phoneNumber("01012345678")
                .nickname("owner")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .build();
        Reservation reservation = Reservation.builder()
                .id(1L)
                .member(owner)
                .reservationStatus(PENDING_PAYMENT)
                .build();
        when(reservationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(reservation));

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.confirmReservation("0xother", new ReservationCheckRequest(1L)));
    }

    @Test
    void cleanupExpiredReservationAssignsSchedulerIdsAndClearsMdcWhenNoExpiredReservations() {
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicReference<String> correlationId = new AtomicReference<>();

        when(reservationRepository.findExpiredReservationIdsBefore(any(LocalDateTime.class), any(PageRequest.class)))
                .thenAnswer(invocation -> {
                    runId.set(MDC.get(TracingConstants.RUN_ID_MDC_KEY));
                    correlationId.set(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY));
                    return List.of();
                });

        reservationService.cleanupExpiredReservation();

        assertThat(runId.get()).isNotBlank();
        assertThat(correlationId.get()).isEqualTo("cleanup:" + runId.get());
        assertThat(MDC.get(TracingConstants.RUN_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void cleanupExpiredReservationKeepsSchedulerIdsDuringSeatRecovery() {
        Reservation reservation = mock(Reservation.class);
        ReservedSeat reservedSeat = mock(ReservedSeat.class);
        Seat seat = mock(Seat.class);
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicReference<String> correlationId = new AtomicReference<>();

        when(reservationRepository.findExpiredReservationIdsBefore(any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(1L));
        when(reservationRepository.findByIdInWithSeats(List.of(1L))).thenReturn(List.of(reservation));
        when(reservation.getReservedSeats()).thenReturn(List.of(reservedSeat));
        when(reservedSeat.getSeat()).thenReturn(seat);

        doAnswer(invocation -> {
            runId.set(MDC.get(TracingConstants.RUN_ID_MDC_KEY));
            correlationId.set(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY));
            return null;
        }).when(seatService).changeSeatsState(eq(List.of(seat)), eq(AVAILABLE));

        reservationService.cleanupExpiredReservation();

        verify(seatService).changeSeatsState(List.of(seat), AVAILABLE);
        verify(reservationRepository).deleteAll(List.of(reservation));
        assertThat(runId.get()).isNotBlank();
        assertThat(correlationId.get()).isEqualTo("cleanup:" + runId.get());
        assertThat(MDC.get(TracingConstants.RUN_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(TracingConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }
}
