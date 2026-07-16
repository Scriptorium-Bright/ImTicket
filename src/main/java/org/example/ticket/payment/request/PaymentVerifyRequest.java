package org.example.ticket.payment.request;

import jakarta.validation.constraints.NotBlank;

public record PaymentVerifyRequest(@NotBlank String providerTransactionId) {
}
