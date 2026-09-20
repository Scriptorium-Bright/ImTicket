package org.example.ticket.reservation.booking.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEventDraft;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleEventWriter;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.response.PaymentVerificationResponse;
import org.example.ticket.reservation.booking.cache.SeatMapInvalidationPublisher;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.domain.Seat;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.repository.SeatRepository;
import org.example.ticket.reservation.booking.util.ReservationValidator;
import org.example.ticket.util.constant.ReservationStatus;
import org.example.ticket.util.constant.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 결제 승인 결과를 예약 완료로 반영하는 애플리케이션 오케스트레이터다.
 *
 * 결제 도메인은 PG 승인 검증과 결제 상태를 책임지고, 이 서비스는 승인된
 * 결과를 예약, 좌석과 결제 주문의 최종 상태로 함께 반영한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationCompletionService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final SeatMapInvalidationPublisher seatMapInvalidationPublisher;
    private final LifecycleEventWriter lifecycleEventWriter;

    /**
     * PG에서 검증된 결제 정보를 예약, 좌석과 결제 주문에 하나의 트랜잭션으로 반영한다.
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
            boolean expiredDuringPaymentVerification = reservation.getReservationStatus()
                    == ReservationStatus.PENDING_PAYMENT;
            if (expiredDuringPaymentVerification) {
                reservation.expire();
                seats.forEach(seat -> seat.markAsReserved(SeatStatus.AVAILABLE));
                seatMapInvalidationPublisher.publishForSeats(seats);
            }
            order.markPaidUnapplied();
            order.markRefundPending();
            lifecycleEventWriter.recordDecision(
                    reservation,
                    expiredPaymentEvents(order, attempt, seats, expiredDuringPaymentVerification)
            );
            return PaymentVerificationResponse.of(order, reservation, attempt.getProviderTransactionId());
        }

        ReservationValidator.validateConfirmable(reservation, walletAddress, now);

        order.markPaidUnapplied();
        reservation.manageReservationStatus(ReservationStatus.SUCCESS, null);
        seats.forEach(seat -> seat.markAsReserved(SeatStatus.RESERVED));
        seatMapInvalidationPublisher.publishForSeats(seats);
        order.markApplied();
        lifecycleEventWriter.recordDecision(reservation, completedPaymentEvents(order, attempt, seats));

        return PaymentVerificationResponse.of(order, reservation, snapshot.providerTransactionId());
    }

    /**
     * PG 거래 ID가 다른 주문에 연결되지 않았는지 확인한 뒤, 해당 주문의 최신 결제 시도에 승인 정보를 기록한다.
     * 검증된 시도 entity를 반환해 후속 예약 상태 적용과 replay 응답에 사용한다.
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

    /**
     * 예약이 이미 만료됐거나 결제 대기 deadline을 지났는지 확인한다.
     * 결제 적용 전에 예약을 만료 처리할지 결정하는 기준으로 사용한다.
     */
    private boolean isExpired(Reservation reservation, LocalDateTime now) {
        if (reservation.getReservationStatus() == ReservationStatus.EXPIRED) {
            return true;
        }
        return reservation.getReservationStatus() == ReservationStatus.PENDING_PAYMENT
                && (reservation.getExpiredTime() == null || reservation.getExpiredTime().isBefore(now));
    }

    /**
     * 결제 주문이 동일 승인 요청을 재응답할 수 있는 종결 상태인지 확인한다.
     * 적용 완료와 환불 처리 상태의 replay만 허용한다.
     */
    private boolean isReplayableTerminalStatus(PaymentOrderStatus status) {
        return status == PaymentOrderStatus.APPLIED
                || status == PaymentOrderStatus.REFUND_PENDING
                || status == PaymentOrderStatus.REFUNDED;
    }

    /**
     * PG 검증 결과의 주문 번호, 금액과 통화를 내부 주문과 비교한다.
     * 하나라도 다르면 결제 상세 불일치 오류로 처리를 중단한다.
     */
    private void validateSnapshot(PaymentOrder order, VerifiedPaymentSnapshot snapshot) {
        if (!order.getMerchantOrderId().equals(snapshot.merchantOrderId())
                || !order.getAmount().equals(snapshot.approvedAmount())
                || !order.getCurrency().equals(snapshot.approvedCurrency())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
        }
    }

    /**
     * 요청 wallet과 결제 주문 소유자의 주소가 같은지 확인한다.
     * 주소 비교에는 대소문자를 구분하지 않는 현재 회원 규칙을 적용한다.
     */
    private boolean isOwner(PaymentOrder order, String walletAddress) {
        return order.getMember() != null
                && order.getMember().getWalletAddress() != null
                && order.getMember().getWalletAddress().equalsIgnoreCase(walletAddress);
    }

    /**
     * 유효한 승인 결제의 완료 결과를 두 사건으로 표현한다.
     * 승인 기록은 먼저 배치하고 예약·좌석·주문 완료 변경을 이어서 기록한다.
     */
    private List<LifecycleEventDraft> completedPaymentEvents(
            PaymentOrder order,
            PaymentAttempt attempt,
            List<Seat> seats
    ) {
        return List.of(
                paymentApprovedEvent(order, attempt),
                new LifecycleEventDraft(
                        LifecycleEventType.RESERVATION_COMPLETED,
                        LifecycleActorType.PAYMENT_VERIFICATION,
                        order.getId(),
                        attempt.getId(),
                        new LifecycleEventPayload(
                                seatIds(seats),
                                completionStateChanges(order, seats),
                                "PAYMENT_APPLIED_TO_RESERVATION"
                        )
                )
        );
    }

    /**
     * 만료된 예약에 도착한 승인 결과를 사건 묶음으로 구성한다.
     * 결제 반영 중 만료된 경우에는 만료 사건도 같은 결정에 넣는다.
     */
    private List<LifecycleEventDraft> expiredPaymentEvents(
            PaymentOrder order,
            PaymentAttempt attempt,
            List<Seat> seats,
            boolean expiredDuringPaymentVerification
    ) {
        List<LifecycleEventDraft> events = new ArrayList<>();
        events.add(paymentApprovedEvent(order, attempt));
        if (expiredDuringPaymentVerification) {
            events.add(new LifecycleEventDraft(
                    LifecycleEventType.RESERVATION_EXPIRED,
                    LifecycleActorType.PAYMENT_VERIFICATION,
                    order.getId(),
                    attempt.getId(),
                    new LifecycleEventPayload(
                            seatIds(seats),
                            expirationStateChanges(order.getReservation().getId(), seats),
                            "PAYMENT_DEADLINE_EXCEEDED"
                    )
            ));
        }
        events.add(new LifecycleEventDraft(
                LifecycleEventType.PAYMENT_REFUND_PENDING,
                LifecycleActorType.PAYMENT_VERIFICATION,
                order.getId(),
                attempt.getId(),
                new LifecycleEventPayload(
                        List.of(),
                        List.of(LifecycleStateChange.changed(
                                LifecycleEntityType.PAYMENT_ORDER,
                                order.getId(),
                                PaymentOrderStatus.READY.name(),
                                PaymentOrderStatus.REFUND_PENDING.name()
                        )),
                        "PAYMENT_APPROVED_AFTER_EXPIRATION"
                )
        ));
        return List.copyOf(events);
    }

    /**
     * PG 검증 결과가 결제 시도에 반영된 사실을 사건으로 구성한다.
     * 예약 완료 또는 환불 대기 결정 앞에 항상 배치된다.
     */
    private LifecycleEventDraft paymentApprovedEvent(PaymentOrder order, PaymentAttempt attempt) {
        return new LifecycleEventDraft(
                LifecycleEventType.PAYMENT_APPROVED,
                LifecycleActorType.PAYMENT_VERIFICATION,
                order.getId(),
                attempt.getId(),
                new LifecycleEventPayload(
                        List.of(),
                        List.of(LifecycleStateChange.changed(
                                LifecycleEntityType.PAYMENT_ATTEMPT,
                                attempt.getId(),
                                "READY",
                                "PAID"
                        )),
                        "PAYMENT_APPROVED"
                )
        );
    }

    /**
     * 정상 결제 완료에서 함께 바뀌는 예약·좌석·주문 상태를 보존한다.
     * 내부의 `PAID_UNAPPLIED` 중간 상태는 독립 사건으로 남기지 않는다.
     */
    private List<LifecycleStateChange> completionStateChanges(PaymentOrder order, List<Seat> seats) {
        List<LifecycleStateChange> changes = new ArrayList<>();
        changes.add(LifecycleStateChange.changed(
                LifecycleEntityType.RESERVATION,
                order.getReservation().getId(),
                ReservationStatus.PENDING_PAYMENT.name(),
                ReservationStatus.SUCCESS.name()
        ));
        seats.forEach(seat -> changes.add(LifecycleStateChange.changed(
                LifecycleEntityType.SEAT,
                seat.getId(),
                SeatStatus.LOCKED.name(),
                SeatStatus.RESERVED.name()
        )));
        changes.add(LifecycleStateChange.changed(
                LifecycleEntityType.PAYMENT_ORDER,
                order.getId(),
                PaymentOrderStatus.READY.name(),
                PaymentOrderStatus.APPLIED.name()
        ));
        return List.copyOf(changes);
    }

    /**
     * 결제 검증 서비스가 만료를 판단한 경우의 예약·좌석 상태 전이를 만든다.
     * 스케줄러 만료는 별도 서비스에서 같은 계약을 사용한다.
     */
    private List<LifecycleStateChange> expirationStateChanges(Long reservationId, List<Seat> seats) {
        List<LifecycleStateChange> changes = new ArrayList<>();
        changes.add(LifecycleStateChange.changed(
                LifecycleEntityType.RESERVATION,
                reservationId,
                ReservationStatus.PENDING_PAYMENT.name(),
                ReservationStatus.EXPIRED.name()
        ));
        seats.forEach(seat -> changes.add(LifecycleStateChange.changed(
                LifecycleEntityType.SEAT,
                seat.getId(),
                SeatStatus.LOCKED.name(),
                SeatStatus.AVAILABLE.name()
        )));
        return List.copyOf(changes);
    }

    /**
     * 사건 payload에 보존할 좌석 식별자 목록을 현재 잠금 목록에서 만든다.
     * 잠금 조회 순서를 유지해 Timeline의 payload도 결정적으로 직렬화한다.
     */
    private List<Long> seatIds(List<Seat> seats) {
        return seats.stream().map(Seat::getId).toList();
    }
}
