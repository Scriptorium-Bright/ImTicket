package org.example.ticket.payment.dto;

import java.time.LocalDateTime;

public record VerifiedPaymentSnapshot(
        String merchantOrderId,
        String providerTransactionId,
        Integer approvedAmount,
        String approvedCurrency,
        LocalDateTime approvedAt
) {
}
