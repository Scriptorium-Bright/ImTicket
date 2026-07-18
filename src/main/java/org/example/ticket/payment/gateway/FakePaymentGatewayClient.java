package org.example.ticket.payment.gateway;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.exception.PaymentErrorCode;

import java.time.LocalDateTime;

public class FakePaymentGatewayClient implements PaymentGatewayClient {

    public static final String PROVIDER = "FAKE";
    private static final String TOKEN_PREFIX = "fake:";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String providerPaymentId(PaymentAuthorization authorization) {
        return approvalToken(authorization.merchantOrderId());
    }

    @Override
    public VerifiedPaymentSnapshot verify(PaymentAuthorization authorization, String providerPaymentId) {
        String expectedToken = approvalToken(authorization.merchantOrderId());
        if (!expectedToken.equals(providerPaymentId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
        }

        return new VerifiedPaymentSnapshot(
                authorization.merchantOrderId(),
                providerPaymentId,
                authorization.amount(),
                authorization.currency(),
                LocalDateTime.now()
        );
    }

    public static String approvalToken(String merchantOrderId) {
        return TOKEN_PREFIX + merchantOrderId;
    }
}
