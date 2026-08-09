package org.example.ticket.reservation.model;

public enum ReservationIdempotencyStatus {

    PROCESSING,
    SUCCEEDED,
    FAILED_RETRYABLE
}
