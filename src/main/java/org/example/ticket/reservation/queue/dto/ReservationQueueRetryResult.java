package org.example.ticket.reservation.queue.dto;

/** Retry scheduling의 Redis 상태 전이 결과다. */
public enum ReservationQueueRetryResult {
    SCHEDULED(true),
    EXHAUSTED(true),
    ALREADY_TERMINAL(true),
    OWNER_MISMATCH(false),
    STATE_MISMATCH(false),
    MISSING(false);

    private final boolean allowsAck;

    /**
     * Redis 전이 결과와 현재 Stream entry의 ACK 가능 여부를 연결한다.
     * Retry 연결이나 terminal 상태가 보장된 결과만 true를 가진다.
     */
    ReservationQueueRetryResult(boolean allowsAck) {
        this.allowsAck = allowsAck;
    }

    /**
     * 현재 Stream entry를 ACK해도 되는 상태 전이인지 반환한다.
     * Redis에 재처리 또는 terminal 연결이 남은 경우에만 true다.
     */
    public boolean allowsAck() {
        return allowsAck;
    }
}
