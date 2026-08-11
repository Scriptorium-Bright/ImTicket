package org.example.ticket.reservation.common.policy;

import java.time.Duration;

/** Redis 작업 lease와 MySQL claim lease가 지켜야 하는 공통 시간 관계다. */
public record ReservationProcessingLeasePolicy(
        Duration claimLease,
        Duration queueLease
) {

    /**
     * 두 lease가 양수이고 DB claim lease가 Queue processing lease 이상인지 검증한다.
     * 잘못된 시간 관계는 Worker가 시작되기 전 설정 단계에서 거절한다.
     */
    public ReservationProcessingLeasePolicy {
        requirePositive(claimLease, "claimLease");
        requirePositive(queueLease, "queueLease");
        if (claimLease.compareTo(queueLease) < 0) {
            throw new IllegalArgumentException("claimLease must be at least queueLease");
        }
    }

    /**
     * lease 값이 null이 아니며 0보다 큰지 확인한다.
     * 오류 메시지에 설정 이름을 포함해 시작 실패 원인을 드러낸다.
     */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
