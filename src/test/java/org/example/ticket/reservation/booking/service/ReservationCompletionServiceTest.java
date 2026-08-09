package org.example.ticket.reservation.booking.service;

import org.example.ticket.member.model.Member;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.ReservedSeat;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCompletionServiceTest {

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ReservationCompletionService reservationCompletionService;

    @Test
    void appliesVerifiedPaymentAndCompletesReservationInOneApplicationPath() {
        Member owner = Member.builder()
                .id(7L)
                .walletAddress("0xowner")
                .nickname("owner")
                .role("ROLE_USER")
                .build();
        PerformanceTime performanceTime = PerformanceTime.builder()
                .performance(Performance.builder().build())
                .build();
        Seat seat = Seat.builder()
                .id(11L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(45000)
                .seatStatus(SeatStatus.LOCKED)
                .performanceTime(performanceTime)
                .build();
        Reservation reservation = Reservation.builder()
                .id(10L)
                .member(owner)
                .totalPrice(45000)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .expiredTime(LocalDateTime.now().plusMinutes(5))
                .reservedSeats(List.of(ReservedSeat.builder().seat(seat).build()))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(1L)
                .reservation(reservation)
                .member(owner)
                .merchantOrderId("imt-order-1")
                .amount(45000)
                .currency("KRW")
                .status(PaymentOrderStatus.READY)
                .build();
        PaymentAttempt attempt = PaymentAttempt.builder()
                .id(2L)
                .paymentOrder(order)
                .attemptId("attempt-1")
                .provider("FAKE")
                .status(PaymentAttemptStatus.READY)
                .build();

        when(paymentOrderRepository.findReservationIdById(1L)).thenReturn(Optional.of(10L));
        when(reservationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(reservation));
        when(seatRepository.findIdsByReservationIds(List.of(10L))).thenReturn(List.of(11L));
        when(seatRepository.findByIdsForUpdate(List.of(11L))).thenReturn(List.of(seat));
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByProviderTransactionId("fake:imt-order-1"))
                .thenReturn(Optional.empty());
        when(paymentAttemptRepository.findTopByPaymentOrderIdOrderByCreatedAtDesc(1L))
                .thenReturn(Optional.of(attempt));

        var response = reservationCompletionService.complete(
                1L,
                "0xOWNER",
                new VerifiedPaymentSnapshot(
                        "imt-order-1", "fake:imt-order-1", 45000, "KRW", LocalDateTime.now()
                )
        );

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.APPLIED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.SUCCESS);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.RESERVED);
        assertThat(response.getReservationStatus()).isEqualTo(ReservationStatus.SUCCESS);
        verify(reservationRepository).findByIdForUpdate(10L);
        verify(seatRepository).findByIdsForUpdate(List.of(11L));
        verify(paymentOrderRepository).findByIdForUpdate(1L);
    }

    @Test
    void preservesLateApprovalAsRefundPendingAfterReservationExpired() {
        Member owner = Member.builder()
                .id(7L)
                .walletAddress("0xowner")
                .nickname("owner")
                .role("ROLE_USER")
                .build();
        Seat seat = Seat.builder()
                .id(11L)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(45000)
                .seatStatus(SeatStatus.AVAILABLE)
                .build();
        Reservation reservation = Reservation.builder()
                .id(10L)
                .member(owner)
                .totalPrice(45000)
                .reservationStatus(ReservationStatus.EXPIRED)
                .expiredTime(LocalDateTime.now().minusMinutes(1))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(1L)
                .reservation(reservation)
                .member(owner)
                .merchantOrderId("imt-order-1")
                .amount(45000)
                .currency("KRW")
                .status(PaymentOrderStatus.READY)
                .build();
        PaymentAttempt attempt = PaymentAttempt.builder()
                .id(2L)
                .paymentOrder(order)
                .attemptId("attempt-1")
                .provider("FAKE")
                .status(PaymentAttemptStatus.READY)
                .build();

        when(paymentOrderRepository.findReservationIdById(1L)).thenReturn(Optional.of(10L));
        when(reservationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(reservation));
        when(seatRepository.findIdsByReservationIds(List.of(10L))).thenReturn(List.of(11L));
        when(seatRepository.findByIdsForUpdate(List.of(11L))).thenReturn(List.of(seat));
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByProviderTransactionId("fake:imt-order-1"))
                .thenReturn(Optional.empty());
        when(paymentAttemptRepository.findTopByPaymentOrderIdOrderByCreatedAtDesc(1L))
                .thenReturn(Optional.of(attempt));

        var response = reservationCompletionService.complete(
                1L,
                "0xOWNER",
                new VerifiedPaymentSnapshot(
                        "imt-order-1", "fake:imt-order-1", 45000, "KRW", LocalDateTime.now()
                )
        );

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUND_PENDING);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PAID);
        assertThat(attempt.getProviderTransactionId()).isEqualTo("fake:imt-order-1");
        assertThat(response.getPaymentStatus()).isEqualTo(PaymentOrderStatus.REFUND_PENDING);
    }
}
