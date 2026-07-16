package org.example.ticket.payment.gateway;

public record PaymentAuthorization(
        Long paymentOrderId,
        Long reservationId,
        String merchantOrderId,
        Integer amount,
        String currency
) {
}
