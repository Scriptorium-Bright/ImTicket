package org.example.ticket.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.payment.constant.PaymentAttemptStatus;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.gateway.FakePaymentGatewayClient;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.request.PaymentPrepareRequest;
import org.example.ticket.payment.response.PaymentPrepareResponse;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.reservation.validation.ReservationValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentPreparationService {

    private static final String CURRENCY_KRW = "KRW";

    private final MemberRepository memberRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;

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

        Reservation reservation = reservationRepository.findByIdWithDetails(request.reservationId())
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

        paymentAttemptRepository.save(PaymentAttempt.builder()
                .paymentOrder(order)
                .attemptId(UUID.randomUUID().toString())
                .provider(FakePaymentGatewayClient.PROVIDER)
                .status(PaymentAttemptStatus.READY)
                .build());

        return response(order);
    }

    private PaymentPrepareResponse response(PaymentOrder order) {
        return PaymentPrepareResponse.from(
                order,
                FakePaymentGatewayClient.PROVIDER,
                FakePaymentGatewayClient.approvalToken(order.getMerchantOrderId())
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
}
