package org.example.ticket.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.constant.PaymentOrderStatus;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.gateway.PaymentAuthorization;
import org.example.ticket.payment.gateway.PaymentGatewayClient;
import org.example.ticket.payment.gateway.VerifiedPaymentSnapshot;
import org.example.ticket.payment.model.PaymentAttempt;
import org.example.ticket.payment.model.PaymentOrder;
import org.example.ticket.payment.repository.PaymentAttemptRepository;
import org.example.ticket.payment.repository.PaymentOrderRepository;
import org.example.ticket.payment.request.PaymentVerifyRequest;
import org.example.ticket.payment.response.PaymentStatusResponse;
import org.example.ticket.payment.response.PaymentVerificationResponse;
import org.example.ticket.reservation.service.ReservationCompletionService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentVerificationService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final ReservationCompletionService reservationCompletionService;

    public PaymentVerificationResponse verify(String walletAddress, Long paymentOrderId,
                                               PaymentVerifyRequest request) {
        PaymentOrder order = paymentOrderRepository.findByIdWithOwner(paymentOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        validateOwner(order, walletAddress);

        if (order.getStatus() == PaymentOrderStatus.APPLIED) {
            String providerTransactionId = paymentAttemptRepository
                    .findTopByPaymentOrderIdOrderByCreatedAtDesc(paymentOrderId)
                    .map(PaymentAttempt::getProviderTransactionId)
                    .orElse(null);
            return PaymentVerificationResponse.of(order, order.getReservation(), providerTransactionId);
        }

        PaymentAuthorization authorization = new PaymentAuthorization(
                order.getId(),
                order.getReservation().getId(),
                order.getMerchantOrderId(),
                order.getAmount(),
                order.getCurrency()
        );
        VerifiedPaymentSnapshot snapshot = paymentGatewayClient.verify(
                authorization,
                request.providerTransactionId()
        );

        return reservationCompletionService.complete(paymentOrderId, walletAddress, snapshot);
    }

    public PaymentStatusResponse getStatus(String walletAddress, Long paymentOrderId) {
        PaymentOrder order = paymentOrderRepository.findByIdWithOwner(paymentOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));
        validateOwner(order, walletAddress);
        String providerTransactionId = paymentAttemptRepository
                .findTopByPaymentOrderIdOrderByCreatedAtDesc(paymentOrderId)
                .map(PaymentAttempt::getProviderTransactionId)
                .orElse(null);
        return PaymentStatusResponse.of(order, providerTransactionId);
    }

    private void validateOwner(PaymentOrder order, String walletAddress) {
        if (order.getMember() == null
                || order.getMember().getWalletAddress() == null
                || !order.getMember().getWalletAddress().equalsIgnoreCase(walletAddress)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ORDER_NOT_OWNER);
        }
    }
}
