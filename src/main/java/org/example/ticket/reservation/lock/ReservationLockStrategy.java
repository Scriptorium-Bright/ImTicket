package org.example.ticket.reservation.lock;

public enum ReservationLockStrategy {
    CONFIGURED,
    PESSIMISTIC,
    SYNCHRONIZED,
    REENTRANT,
    OPTIMISTIC,
    MYSQL_NAMED,
    SINGLE_THREAD;

    /** 설정 문자열을 실제 lock 전략 enum으로 변환하고 별칭·잘못된 값을 검증한다. */
    public static ReservationLockStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return REENTRANT;
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
