package org.example.ticket.payment.gateway;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class FakePaymentGatewayClient implements PaymentGatewayClient {

    public static final String PROVIDER = "FAKE";
    private static final String TOKEN_PREFIX = "fake:";

    @Override
    public VerifiedPaymentSnapshot verify(PaymentAuthorization authorization, String providerTransactionId) {
        String expectedToken = approvalToken(authorization.merchantOrderId());
        if (!expectedToken.equals(providerTransactionId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
        }

        return new VerifiedPaymentSnapshot(
                authorization.merchantOrderId(),
                providerTransactionId,
                authorization.amount(),
                authorization.currency(),
                LocalDateTime.now()
        );
    }

    public static String approvalToken(String merchantOrderId) {
        return TOKEN_PREFIX + merchantOrderId;
    }
}
