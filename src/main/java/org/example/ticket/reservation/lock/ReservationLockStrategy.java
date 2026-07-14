package org.example.ticket.reservation.lock;

public enum ReservationLockStrategy {
    PESSIMISTIC,
    SYNCHRONIZED,
    REENTRANT,
    OPTIMISTIC,
    MYSQL_NAMED,
    SINGLE_THREAD;

    public static ReservationLockStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return PESSIMISTIC;
        }

        String normalized = value.trim().toUpperCase().replace('-', '_');
        if (normalized.equals("ADVISORY") || normalized.equals("NAMED")) {
            normalized = MYSQL_NAMED.name();
        }
        return valueOf(normalized);
    }
}
