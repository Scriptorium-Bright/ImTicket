package org.example.ticket.payment.gateway;

public interface PaymentGatewayClient {

    String provider();

    String providerPaymentId(PaymentAuthorization authorization);

    VerifiedPaymentSnapshot verify(PaymentAuthorization authorization, String providerPaymentId);
}
