package org.example.ticket.payment.gateway;

import org.example.ticket.payment.dto.PaymentAuthorization;
import org.example.ticket.payment.dto.VerifiedPaymentSnapshot;

public interface PaymentGatewayClient {

    String provider();

    String providerPaymentId(PaymentAuthorization authorization);

    VerifiedPaymentSnapshot verify(PaymentAuthorization authorization, String providerPaymentId);
}
