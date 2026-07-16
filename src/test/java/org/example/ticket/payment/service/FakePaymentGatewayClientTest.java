package org.example.ticket.payment.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.payment.exception.PaymentErrorCode;
import org.example.ticket.payment.gateway.FakePaymentGatewayClient;
import org.example.ticket.payment.gateway.PaymentAuthorization;
import org.example.ticket.payment.gateway.VerifiedPaymentSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakePaymentGatewayClientTest {

    private final FakePaymentGatewayClient gateway = new FakePaymentGatewayClient();

    @Test
    void verifiesOnlyTheDeterministicApprovalTokenForTheOrder() {
        PaymentAuthorization authorization = new PaymentAuthorization(
                1L, 10L, "imt-order-1", 45000, "KRW"
        );

        VerifiedPaymentSnapshot snapshot = gateway.verify(
                authorization,
                FakePaymentGatewayClient.approvalToken(authorization.merchantOrderId())
        );

        assertThat(snapshot.merchantOrderId()).isEqualTo("imt-order-1");
        assertThat(snapshot.providerTransactionId())
                .isEqualTo("fake:imt-order-1");
        assertThat(snapshot.approvedAmount()).isEqualTo(45000);
        assertThat(snapshot.approvedCurrency()).isEqualTo("KRW");
    }

    @Test
    void rejectsAProviderTransactionTokenForAnotherOrder() {
        PaymentAuthorization authorization = new PaymentAuthorization(
                1L, 10L, "imt-order-1", 45000, "KRW"
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> gateway.verify(authorization, "fake:imt-order-2"));

        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PROVIDER_REJECTED);
    }
}
