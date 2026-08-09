package org.example.ticket.reservation.booking.domain;

public enum ReservationIdempotencyStatus {

    PROCESSING,
    SUCCEEDED,
    FAILED_RETRYABLE
}
