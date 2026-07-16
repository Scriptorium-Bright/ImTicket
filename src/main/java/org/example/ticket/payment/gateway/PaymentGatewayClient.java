package org.example.ticket.payment.gateway;

public interface PaymentGatewayClient {

    VerifiedPaymentSnapshot verify(PaymentAuthorization authorization, String providerTransactionId);
}
