package org.example.ticket.reservation.queue.domain;

/** Redis 예약 대기열 ticket의 처리 상태다. */
public enum ReservationQueueStatus {
    WAITING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED_FINAL,
    EXPIRED;

    /**
     * ticket이 더 이상 처리되지 않는 종료 상태인지 확인한다.
     * 완료, 최종 실패와 만료 상태에서 true를 반환한다.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED_FINAL || this == EXPIRED;
    }

    /**
     * 내부 retry 대기 상태를 클라이언트 공개 상태로 변환한다.
     * RETRY_WAIT은 재처리를 기다리므로 WAITING으로 노출한다.
     */
    public ReservationQueueStatus visibleStatus() {
        return this == RETRY_WAIT ? WAITING : this;
    }

    /**
     * 현재 상태에서 목표 상태로의 전이가 허용되는지 확인한다.
     * 종료 상태와 null 목표는 모든 전이를 거절한다.
     */
    public boolean canTransitionTo(ReservationQueueStatus target) {
        if (target == null || isTerminal()) {
            return false;
        }
        return switch (this) {
            case WAITING -> target == PROCESSING || target == EXPIRED;
            case PROCESSING -> target == SUCCEEDED || target == FAILED_FINAL || target == RETRY_WAIT;
            case RETRY_WAIT -> target == WAITING || target == EXPIRED;
            case SUCCEEDED, FAILED_FINAL, EXPIRED -> false;
        };
    }
}
