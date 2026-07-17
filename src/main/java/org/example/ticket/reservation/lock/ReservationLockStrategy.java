package org.example.ticket.reservation.lock;

public enum ReservationLockStrategy {
    CONFIGURED,
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
        if (normalized.equals(CONFIGURED.name())) {
            throw new IllegalArgumentException(
                    "reservation.lock-strategy에는 configured를 설정할 수 없습니다. 실제 전략을 지정해야 합니다."
            );
        }
        return valueOf(normalized);
    }
}
