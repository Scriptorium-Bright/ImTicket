package org.example.ticket.payment.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record PaymentVerifyRequest(
        @NotBlank
        @JsonAlias("providerTransactionId")
        String providerPaymentId
) {
}
