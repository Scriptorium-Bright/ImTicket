package org.example.ticket.payment.gateway;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.dto.PaymentAuthorization;
import org.example.ticket.payment.dto.PortOnePayment;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;

/**
 * PortOne V2 인증 결제의 서버 조회 검증 adapter다.
 * browser가 준 paymentId를 그대로 신뢰하지 않고, PortOne에서 재조회한 PAID 결과만 snapshot으로 변환한다.
 */
public class PortOnePaymentGatewayClient implements PaymentGatewayClient {

    public static final String PROVIDER = "PORTONE";
    private static final String PAID = "PAID";

    private final RestClient restClient;

    public PortOnePaymentGatewayClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String providerPaymentId(PaymentAuthorization authorization) {
        return authorization.merchantOrderId();
    }

    @Override
    public VerifiedPaymentSnapshot verify(PaymentAuthorization authorization, String providerPaymentId) {
        if (!authorization.merchantOrderId().equals(providerPaymentId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
        }

        PortOnePayment payment = fetch(providerPaymentId);
        if (!PAID.equals(payment.status()) || payment.transactionId() == null || payment.paidAt() == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
        }
        if (!authorization.merchantOrderId().equals(payment.id())
                || payment.amount() == null
                || !authorization.amount().equals(payment.amount().total())
                || !authorization.currency().equals(payment.currency())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_DETAILS_MISMATCH);
        }

        return new VerifiedPaymentSnapshot(
                payment.id(),
                payment.transactionId(),
                payment.amount().total(),
                payment.currency(),
                payment.paidAt().toLocalDateTime()
        );
    }

    private PortOnePayment fetch(String providerPaymentId) {
        try {
            PortOnePayment payment = restClient.get()
                    .uri("/payments/{paymentId}", providerPaymentId)
                    .retrieve()
                    .body(PortOnePayment.class);
            if (payment == null) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
            }
            return payment;
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
        }
    }
}
