package org.example.ticket.reservation.queue.dto;

/** Redis ticket의 PROCESSING claim 결과다. */
public enum ReservationQueueClaimResult {
    CLAIMED,
    RECOVERED,
    ALREADY_OWNED,
    ALREADY_TERMINAL,
    LEASE_ACTIVE,
    MISSING,
    NOT_WAITING,
    PAYLOAD_MISMATCH
}
