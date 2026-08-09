package org.example.ticket.reservation.queue.domain;

/** Redis 예약 대기열 ticket의 처리 상태다. */
public enum ReservationQueueStatus {
    WAITING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED_FINAL,
    EXPIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED_FINAL || this == EXPIRED;
    }

    public ReservationQueueStatus visibleStatus() {
        return this == RETRY_WAIT ? WAITING : this;
    }

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
