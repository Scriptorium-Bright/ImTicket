package org.example.ticket.reservation.queue.dto;

/** Redis ticket의 PROCESSING claim 결과다. */
public enum ReservationQueueClaimResult {
    CLAIMED,
    ALREADY_OWNED,
    MISSING,
    NOT_WAITING,
    PAYLOAD_MISMATCH
}
