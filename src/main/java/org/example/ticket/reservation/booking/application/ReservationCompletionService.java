package org.example.ticket.reservation.booking.application;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.response.PaymentVerificationResponse;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.persistence.ReservationRepository;
import org.example.ticket.reservation.booking.persistence.SeatRepository;
import org.example.ticket.reservation.booking.support.ReservationValidator;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

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
    private final SeatRepository seatRepository;

    /**
     * PG에서 검증된 결제 정보를 예약·좌석·결제 주문에 하나의 트랜잭션으로 반영한다.
     * 이미 종결된 주문은 기존 결과를 반환하고, 예약이 만료된 뒤 승인된 결제는 환불 대기 상태로 전환한다.
     */
    @Transactional
    public PaymentVerificationResponse complete(Long paymentOrderId, String walletAddress,
                                                 VerifiedPaymentSnapshot snapshot) {
        Long reservationId = paymentOrderRepository.findReservationIdById(paymentOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        List<Long> seatIds = seatRepository.findIdsByReservationIds(List.of(reservationId));
        List<Seat> seats = seatIds.isEmpty()
                ? List.of()
                : seatRepository.findByIdsForUpdate(seatIds);
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(paymentOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (!isOwner(order, walletAddress)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_OWNER);
        }

        if (isReplayableTerminalStatus(order.getStatus())) {
            return PaymentVerificationResponse.of(order, reservation, snapshot.providerTransactionId());
        }
        if (order.getStatus() != PaymentOrderStatus.READY
                && order.getStatus() != PaymentOrderStatus.PAID_UNAPPLIED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_FAILED);
        }

        validateSnapshot(order, snapshot);
        PaymentAttempt attempt = recordVerifiedPayment(order, snapshot);

        LocalDateTime now = LocalDateTime.now();
        if (isExpired(reservation, now)) {
            if (reservation.getReservationStatus() == ReservationStatus.PENDING_PAYMENT) {
                reservation.expire();
                seats.forEach(seat -> seat.markAsReserved(SeatStatus.AVAILABLE));
            }
            order.markPaidUnapplied();
            order.markRefundPending();
            return PaymentVerificationResponse.of(order, reservation, attempt.getProviderTransactionId());
        }

        ReservationValidator.validateConfirmable(reservation, walletAddress, now);

        order.markPaidUnapplied();
        reservation.manageReservationStatus(ReservationStatus.SUCCESS, null);
        seats.forEach(seat -> seat.markAsReserved(SeatStatus.RESERVED));
        order.markApplied();

        return PaymentVerificationResponse.of(order, reservation, snapshot.providerTransactionId());
    }

    /**
     * PG 거래 ID가 다른 주문에 연결되지 않았는지 확인한 뒤, 해당 주문의 최신 결제 시도에 승인 정보를 기록한다.
     */
    private PaymentAttempt recordVerifiedPayment(PaymentOrder order, VerifiedPaymentSnapshot snapshot) {
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
        return attempt;
    }

    /** 결제 대기 예약이 이미 만료되었거나 현재 시각까지 결제가 완료되지 않았는지 판단한다. */
    private boolean isExpired(Reservation reservation, LocalDateTime now) {
        if (reservation.getReservationStatus() == ReservationStatus.EXPIRED) {
            return true;
        }
        return reservation.getReservationStatus() == ReservationStatus.PENDING_PAYMENT
                && (reservation.getExpiredTime() == null || reservation.getExpiredTime().isBefore(now));
    }

    /** 동일 결제 승인 요청을 안전하게 재응답할 수 있는 종결 결제 상태인지 판단한다. */
    private boolean isReplayableTerminalStatus(PaymentOrderStatus status) {
        return status == PaymentOrderStatus.APPLIED
                || status == PaymentOrderStatus.REFUND_PENDING
                || status == PaymentOrderStatus.REFUNDED;
    }

    /** PG 검증 결과의 주문 번호·금액·통화가 내부 결제 주문과 일치하는지 검증한다. */
    private void validateSnapshot(PaymentOrder order, VerifiedPaymentSnapshot snapshot) {
        if (!order.getMerchantOrderId().equals(snapshot.merchantOrderId())
                || !order.getAmount().equals(snapshot.approvedAmount())
                || !order.getCurrency().equals(snapshot.approvedCurrency())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
        }
    }

    /** 요청한 지갑 주소가 결제 주문의 소유자 주소와 대소문자 구분 없이 일치하는지 확인한다. */
    private boolean isOwner(PaymentOrder order, String walletAddress) {
        return order.getMember() != null
                && order.getMember().getWalletAddress() != null
                && order.getMember().getWalletAddress().equalsIgnoreCase(walletAddress);
    }
}
