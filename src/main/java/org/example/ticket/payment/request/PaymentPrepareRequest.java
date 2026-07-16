package org.example.ticket.payment.request;

import jakarta.validation.constraints.NotNull;

public record PaymentPrepareRequest(@NotNull Long reservationId) {
}
