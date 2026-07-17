package org.example.ticket.reservation.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.gateway.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.response.PaymentVerificationResponse;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.model.ReservedSeat;
import org.example.ticket.reservation.model.Seat;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.validation.ReservationValidator;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 결제 승인 결과를 예약 완료로 반영하는 애플리케이션 오케스트레이터다.
 *
 * 결제 도메인은 PG 승인 검증과 결제 상태를 책임지고, 이 서비스는 승인된
 * 결과를 예약·좌석·결제 주문의 최종 상태로 함께 반영한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationCompletionService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ReservationRepository reservationRepository;
    private final SeatService seatService;

    @Transactional
    public PaymentVerificationResponse complete(Long paymentOrderId, String walletAddress,
                                                 VerifiedPaymentSnapshot snapshot) {
        PaymentOrder orderReference = paymentOrderRepository.findById(paymentOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        Long reservationId = orderReference.getReservation().getId();
        Reservation reservation = reservationRepository.findByIdWithDetailsForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(paymentOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (!isOwner(order, walletAddress)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_OWNER);
        }

        if (order.getStatus() == PaymentOrderStatus.APPLIED) {
            return PaymentVerificationResponse.of(order, reservation, snapshot.providerTransactionId());
        }
        if (order.getStatus() != PaymentOrderStatus.READY
                && order.getStatus() != PaymentOrderStatus.PAID_UNAPPLIED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_FAILED);
        }

        validateSnapshot(order, snapshot);
        ReservationValidator.validateConfirmable(reservation, walletAddress);

        paymentAttemptRepository.findByProviderTransactionId(snapshot.providerTransactionId())
                .ifPresent(existing -> {
                    if (!existing.getPaymentOrder().getId().equals(order.getId())) {
                        throw new BusinessException(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
                    }
                });

        PaymentAttempt attempt = paymentAttemptRepository
                .findTopByPaymentOrderIdOrderByCreatedAtDesc(order.getId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        attempt.markPaid(
                snapshot.providerTransactionId(),
                snapshot.approvedAmount(),
                snapshot.approvedCurrency(),
                snapshot.approvedAt()
        );

        order.markPaidUnapplied();
        reservation.manageReservationStatus(ReservationStatus.SUCCESS, null);

        List<Seat> seats = reservation.getReservedSeats().stream()
                .map(ReservedSeat::getSeat)
                .toList();
        seatService.changeSeatsState(seats, SeatStatus.RESERVED);
        order.markApplied();

        return PaymentVerificationResponse.of(order, reservation, snapshot.providerTransactionId());
    }

    private void validateSnapshot(PaymentOrder order, VerifiedPaymentSnapshot snapshot) {
        if (!order.getMerchantOrderId().equals(snapshot.merchantOrderId())
                || !order.getAmount().equals(snapshot.approvedAmount())
                || !order.getCurrency().equals(snapshot.approvedCurrency())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
        }
    }

    private boolean isOwner(PaymentOrder order, String walletAddress) {
        return order.getMember() != null
                && order.getMember().getWalletAddress() != null
                && order.getMember().getWalletAddress().equalsIgnoreCase(walletAddress);
    }
}
