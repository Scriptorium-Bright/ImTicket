package org.example.ticket.reservation.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.reservation.exception.ReservationErrorCode;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.request.ReservationCheckRequest;
import org.example.ticket.reservation.request.ReservationRequest;
import org.example.ticket.reservation.response.ReservationCreateResponse;
import org.example.ticket.util.constant.SeatInfo;
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
import static org.example.ticket.util.constant.SeatStatus.LOCKED;
import static org.example.ticket.util.constant.SeatStatus.RESERVED;
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
    void createReservationLocksSeatsWithinRequestedPerformanceTime() {
        Member member = Member.builder()
                .walletAddress("0xowner")
                .phoneNumber("01012345678")
                .nickname("owner")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .build();
        Seat seat1 = Seat.builder()
                .id(1L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(10000)
                .seatStatus(AVAILABLE)
                .build();
        Seat seat2 = Seat.builder()
                .id(3L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(3)
                .seatType(SeatInfo.VIP)
                .price(20000)
                .seatStatus(AVAILABLE)
                .build();

        when(memberRepository.findByWalletAddressIgnoreCase("0xowner")).thenReturn(Optional.of(member));
        when(seatService.findAndLockSeatsByPerformanceTime(10L, List.of(1L, 3L)))
                .thenReturn(List.of(seat1, seat2));

        ReservationCreateResponse response = reservationService.createReservation(
                "0xowner",
                new ReservationRequest(10L, List.of(3L, 1L))
        );

        assertThat(response.getTotalPrice()).isEqualTo(30000);
        verify(seatService).findAndLockSeatsByPerformanceTime(10L, List.of(1L, 3L));
        verify(seatService).changeSeatsState(List.of(seat1, seat2), LOCKED);
        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    void createReservationFindsMemberIgnoringWalletAddressCase() {
        Member member = Member.builder()
                .walletAddress("0xowner")
                .phoneNumber("01012345678")
                .nickname("owner")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .build();
        Seat seat = Seat.builder()
                .id(1L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(10000)
                .seatStatus(AVAILABLE)
                .build();

        when(memberRepository.findByWalletAddressIgnoreCase("0xOWNER")).thenReturn(Optional.of(member));
        when(seatService.findAndLockSeatsByPerformanceTime(10L, List.of(1L))).thenReturn(List.of(seat));

        reservationService.createReservation("0xOWNER", new ReservationRequest(10L, List.of(1L)));

        verify(memberRepository).findByWalletAddressIgnoreCase("0xOWNER");
    }

    @Test
    void createReservationRejectsDuplicateSeatIds() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> reservationService.createReservation("0xowner", new ReservationRequest(10L, List.of(1L, 1L))));

        assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.DUPLICATE_SEAT_INCLUDED);
    }

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

        BusinessException exception = assertThrows(BusinessException.class,
                () -> reservationService.confirmReservation("0xother", new ReservationCheckRequest(1L)));

        assertThat(exception.getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_NOT_OWNER);
    }

    @Test
    void confirmReservationAcceptsSameWalletAddressWithDifferentCase() {
        Member owner = Member.builder()
                .walletAddress("0xowner")
                .phoneNumber("01012345678")
                .nickname("owner")
                .smsVerified(true)
                .walletVerified(true)
                .role("ROLE_USER")
                .build();
        PerformanceTime performanceTime = PerformanceTime.builder()
                .performance(Performance.builder().build())
                .build();
        Seat seat = Seat.builder()
                .id(1L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(10000)
                .seatStatus(LOCKED)
                .performanceTime(performanceTime)
                .build();
        Reservation reservation = Reservation.builder()
                .id(1L)
                .member(owner)
                .reservationStatus(PENDING_PAYMENT)
                .expiredTime(LocalDateTime.now().plusMinutes(1))
                .reservedSeats(List.of(ReservedSeat.builder().seat(seat).build()))
                .build();

        when(reservationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(reservation));

        reservationService.confirmReservation("0xOWNER", new ReservationCheckRequest(1L));

        verify(seatService).changeSeatsState(List.of(seat), RESERVED);
    }

    @Test
    void cleanupExpiredReservationAssignsSchedulerIdsAndClearsMdcWhenNoExpiredReservations() {
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicReference<String> correlationId = new AtomicReference<>();

        when(reservationRepository.findExpiredReservationIdsBefore(eq(PENDING_PAYMENT), any(LocalDateTime.class), any(PageRequest.class)))
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

        when(reservationRepository.findExpiredReservationIdsBefore(eq(PENDING_PAYMENT), any(LocalDateTime.class), any(PageRequest.class)))
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
