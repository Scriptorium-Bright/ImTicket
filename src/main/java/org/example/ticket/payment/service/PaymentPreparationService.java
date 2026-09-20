package org.example.ticket.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.lifecycle.event.LifecycleActorType;
import org.example.ticket.lifecycle.event.LifecycleEntityType;
import org.example.ticket.lifecycle.event.LifecycleEventDraft;
import org.example.ticket.lifecycle.event.LifecycleEventPayload;
import org.example.ticket.lifecycle.event.LifecycleEventType;
import org.example.ticket.lifecycle.event.LifecycleEventWriter;
import org.example.ticket.lifecycle.event.LifecycleStateChange;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.dto.PaymentAuthorization;
import org.example.ticket.payment.gateway.PaymentGatewayClient;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.request.PaymentPrepareRequest;
import org.example.ticket.payment.response.PaymentPrepareResponse;
import org.example.ticket.reservation.booking.domain.Reservation;
import org.example.ticket.reservation.booking.repository.ReservationRepository;
import org.example.ticket.reservation.booking.util.ReservationValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentPreparationService {

    private static final String CURRENCY_KRW = "KRW";

    private final MemberRepository memberRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final LifecycleEventWriter lifecycleEventWriter;

    @Transactional
    public PaymentPrepareResponse prepare(String walletAddress, PaymentPrepareRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        Member member = memberRepository.findByWalletAddressIgnoreCase(walletAddress)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_OWNER));

        String requestHash = sha256(request.reservationId().toString());
        var existingOrder = paymentOrderRepository.findByMemberIdAndIdempotencyKey(member.getId(), idempotencyKey);
        if (existingOrder.isPresent()) {
            PaymentOrder order = existingOrder.get();
            if (!requestHash.equals(order.getRequestHash())) {
                throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return response(order);
        }

        Reservation reservation = reservationRepository.findByIdForUpdate(request.reservationId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        ReservationValidator.validateConfirmable(reservation, walletAddress);

        PaymentOrder order = PaymentOrder.builder()
                .reservation(reservation)
                .member(member)
                .merchantOrderId("imt-" + reservation.getReservationCode() + "-" + UUID.randomUUID())
                .amount(reservation.getTotalPrice())
                .currency(CURRENCY_KRW)
                .status(PaymentOrderStatus.READY)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .build();
        paymentOrderRepository.save(order);

        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentOrder(order)
                .attemptId(UUID.randomUUID().toString())
                .provider(paymentGatewayClient.provider())
                .status(PaymentAttemptStatus.READY)
                .build();
        paymentAttemptRepository.save(attempt);
        lifecycleEventWriter.recordDecision(reservation, List.of(paymentPreparedEvent(order, attempt)));

        return response(order);
    }

    private PaymentPrepareResponse response(PaymentOrder order) {
        PaymentAuthorization authorization = new PaymentAuthorization(
                order.getId(),
                order.getReservation().getId(),
                order.getMerchantOrderId(),
                order.getAmount(),
                order.getCurrency()
        );
        return PaymentPrepareResponse.from(
                order,
                paymentGatewayClient.provider(),
                paymentGatewayClient.providerPaymentId(authorization)
        );
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private LifecycleEventDraft paymentPreparedEvent(PaymentOrder order, PaymentAttempt attempt) {
        List<LifecycleStateChange> stateChanges = List.of(
                LifecycleStateChange.created(
                        LifecycleEntityType.PAYMENT_ORDER,
                        order.getId(),
                        PaymentOrderStatus.READY.name()
                ),
                LifecycleStateChange.created(
                        LifecycleEntityType.PAYMENT_ATTEMPT,
                        attempt.getId(),
                        PaymentAttemptStatus.READY.name()
                )
        );
        return new LifecycleEventDraft(
                LifecycleEventType.PAYMENT_PREPARED,
                LifecycleActorType.PAYMENT_PREPARATION,
                order.getId(),
                attempt.getId(),
                new LifecycleEventPayload(List.of(), stateChanges, "PAYMENT_PREPARED")
        );
    }
}
