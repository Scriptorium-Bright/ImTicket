package org.example.ticket.payment.dto;

public record PaymentAuthorization(
        Long paymentOrderId,
        Long reservationId,
        String merchantOrderId,
        Integer amount,
        String currency
) {
}
