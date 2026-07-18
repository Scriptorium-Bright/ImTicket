package org.example.ticket.payment.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.gateway.FakePaymentGatewayClient;
import org.example.ticket.payment.gateway.PaymentAuthorization;
import org.example.ticket.payment.gateway.PaymentGatewayClient;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.request.PaymentPrepareRequest;
import org.example.ticket.performance.model.Performance;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PaymentPreparationServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @InjectMocks
    private PaymentPreparationService paymentPreparationService;

    @BeforeEach
    void setUpGateway() {
        lenient().when(paymentGatewayClient.provider()).thenReturn(FakePaymentGatewayClient.PROVIDER);
        lenient().when(paymentGatewayClient.providerPaymentId(any(PaymentAuthorization.class)))
                .thenAnswer(invocation -> FakePaymentGatewayClient.approvalToken(
                        invocation.getArgument(0, PaymentAuthorization.class).merchantOrderId()
                ));
    }

    @Test
    void createsServerPricedOrderAndFakeApprovalToken() {
        Member member = owner();
        Reservation reservation = pendingReservation(member);
        when(memberRepository.findByWalletAddressIgnoreCase("0xOWNER")).thenReturn(Optional.of(member));
        when(paymentOrderRepository.findByMemberIdAndIdempotencyKey(7L, "key-1"))
                .thenReturn(Optional.empty());
        when(reservationRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(reservation));

        var response = paymentPreparationService.prepare(
                "0xOWNER", new PaymentPrepareRequest(10L), "key-1"
        );

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentOrderRepository).save(captor.capture());
        PaymentOrder savedOrder = captor.getValue();

        assertThat(savedOrder.getAmount()).isEqualTo(45000);
        assertThat(savedOrder.getStatus()).isEqualTo(PaymentOrderStatus.READY);
        assertThat(savedOrder.getCurrency()).isEqualTo("KRW");
        assertThat(response.getProviderPaymentId())
                .isEqualTo("fake:" + savedOrder.getMerchantOrderId());
        verify(paymentAttemptRepository).save(any());
    }

    @Test
    void replaysTheSameOrderForTheSameIdempotencyKey() {
        Member member = owner();
        Reservation reservation = pendingReservation(member);
        PaymentOrder existing = PaymentOrder.builder()
                .id(1L)
                .reservation(reservation)
                .member(member)
                .merchantOrderId("imt-existing")
                .amount(45000)
                .currency("KRW")
                .status(PaymentOrderStatus.READY)
                .idempotencyKey("key-1")
                .requestHash("4a44dc15364204a80fe80e9039455cc1608281820fe2b24f1e5233ade6af1dd5")
                .build();
        when(memberRepository.findByWalletAddressIgnoreCase("0xowner")).thenReturn(Optional.of(member));
        when(paymentOrderRepository.findByMemberIdAndIdempotencyKey(7L, "key-1"))
                .thenReturn(Optional.of(existing));

        var response = paymentPreparationService.prepare(
                "0xowner", new PaymentPrepareRequest(10L), "key-1"
        );

        assertThat(response.getPaymentOrderId()).isEqualTo(1L);
        assertThat(response.getMerchantOrderId()).isEqualTo("imt-existing");
        verify(paymentOrderRepository, never()).save(any());
        verify(paymentAttemptRepository, never()).save(any());
    }

    @Test
    void requiresAnIdempotencyKey() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> paymentPreparationService.prepare("0xowner", new PaymentPrepareRequest(10L), " "));

        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    private Member owner() {
        return Member.builder()
                .id(7L)
                .walletAddress("0xowner")
                .nickname("owner")
                .role("ROLE_USER")
                .build();
    }

    private Reservation pendingReservation(Member member) {
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
        return Reservation.builder()
                .id(10L)
                .reservationCode("reservation-10")
                .member(member)
                .totalPrice(45000)
                .reservationStatus(ReservationStatus.PENDING_PAYMENT)
                .expiredTime(LocalDateTime.now().plusMinutes(5))
                .reservedSeats(List.of(ReservedSeat.builder().seat(seat).build()))
                .build();
    }
}
